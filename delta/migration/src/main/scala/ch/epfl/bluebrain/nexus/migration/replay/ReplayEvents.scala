package ch.epfl.bluebrain.nexus.migration.replay

import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sdk.migration.ToMigrateEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.migration.config.ReplayConfig
import ch.epfl.bluebrain.nexus.migration.replay.ReplayEvents.{State, formatOffset, logger}
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Stream}
import io.circe.jawn._
import monix.bio.{Task, UIO}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

/**
  * Allows to replay all events from a materialized view to run migration
  * @param session          the cassandra session
  * @param settings         the replay settings based on the akka ones
  */
final class ReplayEvents private(session: CassandraSession,
                                 settings: ReplayConfig) {

  private val selectMessages = s"""
      SELECT timestamp, persistence_id, sequence_nr, event, ser_manifest FROM ${settings.keyspace}.ordered_messages WHERE
        timebucket = ? AND
        timestamp > ? AND
        timestamp < ?
        ORDER BY timestamp ASC
        LIMIT ${settings.maxBufferSize}
     """.stripMargin

  /**
    * Returns an infinite stream of messages containing events to migrate starting from the given offset
    * @param start the starting offset
    */
  def run(start: Option[UUID]): Stream[Task, ToMigrateEvent] = {
    val firstOffset = start.getOrElse(settings.firstOffset)
    val startBucket = TimeBucket(firstOffset, settings.bucketSize)

    logger.info(s"Replaying events with settings: $settings")
    logger.info(s"Starting from offset: ${formatOffset(firstOffset)} at bucket ${startBucket.key}")

    Stream
      .unfoldChunkEval[Task, State, ToMigrateEvent](State(firstOffset, startBucket, Set.empty)) {
        case State(from, currentBucket, seenInBucket) =>
          for {
            now                   <- instant
            // Applying the eventual consistency delay
            to                     = Uuids.endOf(now.toEpochMilli - settings.eventualConsistency.toMillis)
            // We fetch events from current bucket
            _                     <- UIO.delay(logger.debug(s"End offset is ${formatOffset(to)}"))
            events                <-
              Task
                .deferFuture(session.selectAll(selectMessages, currentBucket.key.toString, from, to))
                .map { rows =>
                  logger.info(s"We got ${rows.size} rows")
                  parseRows(rows.filterNot { e =>
                    seenInBucket.contains((e.getString("persistence_id"), e.getLong("sequence_nr")))
                  })
                }
                .onErrorRestartIf { e =>
                  logger.error("We got the following error while fetching events, retrying", e)
                  true
                }
            inPast                <- currentBucket.inPast
            // Move on to the next bucket if all its events have been consumed
            // and it is past and the consistency delay has been respected
            (nextBucket, nextSeen) = if (events.isEmpty && inPast && !currentBucket.within(to)) {
              val nextBucket = currentBucket.next()
              logger.info(s"Switching to bucket: ${nextBucket.key}")
              nextBucket -> Set.empty[(String, Long)]
            } else {
              logger.debug(
                s"Keeping bucket: ${currentBucket.key} (${events.size}, ${currentBucket.within(to)})"
              )
              currentBucket -> (seenInBucket ++ events.map(e =>
                (e.persistenceId, e.sequenceNr)
              ))
            }
            nextOffset             = events.lastOption.fold(Uuids.startOf(nextBucket.key)) { _.offset }
            _                     <- UIO.delay(logger.info(s"Next offset is ${formatOffset(nextOffset)}"))
            // If the current bucket is present and if no events have been fetched, we backoff before trying again
            _                     <- Task.when(!inPast && events.isEmpty) {
              UIO.delay(
                logger.info(s"No results for current bucket, waiting for ${settings.refreshInterval}")
              ) >> Task
                .sleep(settings.refreshInterval)
            }
          } yield Some(
            Chunk.seq(events) -> State(nextOffset, nextBucket, nextSeen)
          )
      }
  }

  private def parseRows(rows: Seq[Row]): Seq[ToMigrateEvent] = rows.mapFilter { row =>
    val entityType    = EntityType(row.getString("ser_manifest"))
    val persistenceId = row.getString("persistence_id")
    val sequenceNr    = row.getLong("sequence_nr")
    val payload       = parseByteBuffer(row.getByteBuffer("event"))
    val offset        = row.getUuid("timestamp")

    payload.fold(
      err => {
        logger.error(s"Error while deserializing payload for `$persistenceId:$sequenceNr` for entity $entityType", err)
        None
      },
      value => Some(ToMigrateEvent(entityType, persistenceId, sequenceNr, value, Instant.ofEpochMilli(Uuids.unixTimestamp(offset)), offset))
    )
  }
}

object ReplayEvents {

  private val logger: Logger = Logger[ReplayEvents]

  private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS")

  private def formatOffset(uuid: UUID): String = {
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Uuids.unixTimestamp(uuid)), ZoneOffset.UTC)
    s"$uuid (${timestampFormatter.format(time)})"
  }

  final private case class State(uuid: UUID, timeBucket: TimeBucket, seenInBucket: Set[(String, Long)])

  /**
    * Creates a [[ReplayEvents]] instance
 *
    * @param settings         the replay settings based on the akka ones
    */
  def apply(cassandraSession: CassandraSession, settings: ReplayConfig): ReplayEvents =
    new ReplayEvents(cassandraSession, settings)
}
