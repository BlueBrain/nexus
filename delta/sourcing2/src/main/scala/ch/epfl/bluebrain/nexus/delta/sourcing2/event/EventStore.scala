package ch.epfl.bluebrain.nexus.delta.sourcing2.event

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing2.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.TrackQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityScope, EntityType, Envelope}
import ch.epfl.bluebrain.nexus.delta.sourcing2.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing2.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.{SelectedTrack, Track, TrackStore}
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.Task

import java.time.Instant

/**
  * Fetches/saves events from/to the database
  */
trait EventStore {

  /**
    * Persists the event to the database
    */
  def save(row: EventRow): ConnectionIO[Unit]

  /**
    * Fetches events for the given type/id up to the provided revision
    */
  def currentEventsById[Event](tpe: EntityType, id: EntityId, to: Option[Int])(implicit
      decoder: PayloadDecoder[Event]
  ): Stream[Task, Event]

  /**
    * Fetches events for the given type/id up to the last existing revision
    */
  def currentEventsById[Event](tpe: EntityType, id: EntityId)(implicit
      decoder: PayloadDecoder[Event]
  ): Stream[Task, Event] = currentEventsById(tpe, id, None): Stream[Task, Event]

  /**
    * Fetches events that belongs to the provided track from the provided offset
    *
    * The stream is completed when it reached the end .
    *
    * @param track
    *   the track to query
    * @param offset
    *   the offset to start from
    */
  def currentEventsByTrack[Event](track: Track, offset: Offset)(implicit
      decoder: PayloadDecoder[Event]
  ): Stream[Task, Envelope[Event]]

  /**
    * Fetches events that belongs to the provided track from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param track
    *   the track to query
    * @param offset
    *   the offset to start from
    */
  def eventsByTrack[Event](track: Track, offset: Offset)(implicit
      decoder: PayloadDecoder[Event]
  ): Stream[Task, Envelope[Event]]

}

object EventStore {

  final case class EntityScopeViolation(id: EntityId, scope: EntityScope)

  final private[event] class EventStoreImpl(trackStore: TrackStore, config: TrackQueryConfig, xas: Transactors)
      extends EventStore {

    def save(row: EventRow): ConnectionIO[Unit] =
      sql"""
           | INSERT INTO events (
           |  entity_type,
           |  entity_id,
           |  revision,
           |  scope,
           |  payload,
           |  tracks,
           |  instant,
           |  written_at,
           |  write_version
           | )
           | VALUES (
           |  ${row.tpe},
           |  ${row.id},
           |  ${row.revision},
           |  ${row.scope},
           |  ${row.payload},
           |  ${row.tracks},
           |  ${row.instant},
           |  ${row.writtenAt},
           |  ${row.writeVersion}
           | )
         """.stripMargin.update.run.void

    override def currentEventsById[Event](tpe: EntityType, id: EntityId, to: Option[Int])(implicit
        decoder: PayloadDecoder[Event]
    ): Stream[Task, Event] = {
      val select =
        fr"SELECT payload FROM events WHERE entity_type = $tpe AND entity_id = $id" ++
          Fragments.andOpt(to.map { t => fr"revision <= $t" }) ++
          fr"ORDER BY revision"

      select.query[Json].streamWithChunkSize(config.batchSize).transact(xas.read).flatMap { json =>
        Stream.fromEither[Task](decoder(tpe, json))
      }
    }

    private def eventsByTrack[Event](
        track: Track,
        offset: Offset,
        strategy: RefreshStrategy
    )(implicit decoder: PayloadDecoder[Event]): Stream[Task, Envelope[Event]] = {
      val select = fr"SELECT entity_type, entity_id, payload, revision, instant, ordering FROM events"

      Stream
        .eval(trackStore.select(track))
        .flatMap {
          case SelectedTrack.NotFound                  => Stream.empty
          case selectedTrack: SelectedTrack.ValidTrack =>
            Stream.unfoldChunkEval[Task, Offset, Envelope[Event]](offset) { currentOffset =>
              val query =
                select ++ Fragments.whereAndOpt(selectedTrack.in, currentOffset.after) ++
                  fr"ORDER BY ordering" ++
                  fr"LIMIT ${config.batchSize}"

              query.query[(EntityType, EntityId, Json, Int, Instant, Long)].to[List].transact(xas.tracking).flatMap {
                rows =>
                  Task
                    .fromEither(
                      rows
                        .traverse { case (entityType, entityId, payload, rev, instant, offset) =>
                          decoder(entityType, payload).map { state =>
                            Envelope(
                              entityType,
                              entityId,
                              state,
                              rev,
                              instant,
                              Offset.At(offset)
                            )
                          }
                        }
                    )
                    .flatMap { envelopes =>
                      envelopes.lastOption.fold(
                        strategy match {
                          case RefreshStrategy.Stop         => Task.none
                          case RefreshStrategy.Delay(value) =>
                            Task.sleep(value) >> Task.some((Chunk.empty[Envelope[Event]], currentOffset))
                        }
                      ) { last => Task.some((Chunk.seq(envelopes), last.offset)) }
                    }
              }
            }
        }
    }

    override def currentEventsByTrack[Event](track: Track, offset: Offset)(implicit
        decoder: PayloadDecoder[Event]
    ): Stream[Task, Envelope[Event]] =
      eventsByTrack(track, offset, RefreshStrategy.Stop)

    override def eventsByTrack[Event](track: Track, offset: Offset)(implicit
        decoder: PayloadDecoder[Event]
    ): Stream[Task, Envelope[Event]] =
      eventsByTrack(track, offset, RefreshStrategy.Delay(config.refreshInterval))
  }

  def apply(trackStore: TrackStore, config: TrackQueryConfig, xas: Transactors): EventStore =
    new EventStoreImpl(trackStore, config, xas)

}
