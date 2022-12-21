package ch.epfl.bluebrain.nexus.migration

import akka.actor.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.migration.Migration.{logger, MigrationProgress}
import ch.epfl.bluebrain.nexus.migration.config.ReplayConfig
import ch.epfl.bluebrain.nexus.migration.replay.ReplayEvents
import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.typesafe.scalalogging.Logger
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Stream}
import io.circe.optics.JsonPath.root
import monix.bio.Task
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import java.io.File
import java.time.Instant
import java.util.UUID

final class Migration private (
    replay: ReplayEvents,
    blacklisted: Set[String],
    logs: Set[MigrationLog],
    batch: BatchConfig,
    xas: Transactors
) {

  private val logMap = logs.map { log => log.entityType -> log }.toMap

  private val processEvent: ToMigrateEvent => Task[Unit] = event =>
    logMap.get(event.entityType) match {
      case Some(migrationLog) =>
        migrationLog(event).tapError { e =>
          Task.delay { logger.error(s"[${event.persistenceId}] $e") }
        }
      case None               =>
        val message = s"The logMap has no entry for ${event.entityType}"
        Task.delay { logger.error(message) } >>
          Task.raiseError(new NoSuchElementException(message))
    }

  private def saveProgress(progress: MigrationProgress) =
    sql"""INSERT INTO migration_offset (name, akka_offset, processed, discarded, failed, instant)
         |VALUES ('migration',${progress.offset.toString}, ${progress.processed}, ${progress.discarded}, ${progress.failed}, ${progress.instant})
         |ON CONFLICT (name)
         |DO UPDATE set
         |  akka_offset = EXCLUDED.akka_offset,
         |  processed = EXCLUDED.processed + migration_offset.processed,
         |  discarded = EXCLUDED.discarded + migration_offset.discarded,
         |  failed = EXCLUDED.failed + migration_offset.failed,
         |  instant = EXCLUDED.instant;
         |""".stripMargin.update.run

  private def saveIgnored(events: Chunk[ToMigrateEvent]) = {
    val sql = """INSERT INTO ignored_events (type, persistence_id, sequence_nr, payload, instant, akka_offset)
         |VALUES (?,?,?,?,?,?)
         |""".stripMargin
    Update[ToMigrateEvent](sql).updateMany(events)
  }

  private def fetchProgress =
    sql"""SELECT akka_offset, processed, discarded, failed, instant FROM migration_offset where name = 'migration'"""
      .query[MigrationProgress]
      .option
      .transact(xas.read)

  def start: Stream[Task, Elem[Unit]] =
    Stream
      .eval(fetchProgress)
      .flatMap { progress =>
        replay.run(progress.map(_.offset)).groupWithin(batch.maxElements, batch.maxInterval).evalTapChunk { chunk =>
          chunk.last.traverse { last =>
            val (toIgnore, toProcess) = chunk.partitionEither { e =>
              Either.cond(!Migration.toIgnore(e, blacklisted), e, e)
            }
            val migrated              = toProcess.traverse(processEvent)
            val ignored               = saveIgnored(toIgnore).transact(xas.write)
            val migrationProgress     =
              MigrationProgress(last.offset, last.instant, toProcess.size.toLong, toIgnore.size.toLong, 0L)
            val updateProgress        = saveProgress(migrationProgress).transact(xas.write)

            migrated >> ignored >> updateProgress
          }
        }
      }
      .flatMap(Stream.chunk)
      .map { e =>
        // Forging a bogus elem to run migration in the supervisor
        SuccessElem(
          e.entityType,
          iri"https://bbp.epfl.ch/migration",
          None,
          e.instant,
          Offset.start,
          (),
          e.sequenceNr.toInt
        )
      }
}

object Migration {

  private val logger = Logger[Migration]

  final case class MigrationProgress(offset: UUID, instant: Instant, processed: Long, discarded: Long, failed: Long)

  object MigrationProgress {
    implicit val projectionProgressRowRead: Read[MigrationProgress] = {
      Read[(String, Long, Long, Long, Instant)].map { case (offset, processed, discarded, failed, instant) =>
        MigrationProgress(
          UUID.fromString(offset),
          instant,
          processed,
          discarded,
          failed
        )
      }
    }
  }

  def toIgnore(toMigrateEvent: ToMigrateEvent, projects: Set[String]): Boolean = {
    val payload = toMigrateEvent.payload
    val project = root.project.string

    (if (project.nonEmpty(payload)) project.all(projects.contains)(payload) else false) ||
    (toMigrateEvent.entityType == Acls.entityType &&
      root.address.string.all { address => projects.contains(address.substring(1)) }(payload))
  }

  private case class IgnoreConfig(blacklisted: Set[String])
  private object IgnoreConfig {
    implicit final val ignoreConfigReader: ConfigReader[IgnoreConfig] =
      deriveReader[IgnoreConfig]
  }

  def apply(logs: Set[MigrationLog], xas: Transactors, supervisor: Supervisor, system: ActorSystem): Task[Migration] = {
    val migrationConfigEnvVariable = "MIGRATION_CONF"
    val parseOptions               = ConfigParseOptions.defaults().setAllowMissing(false)
    val externalMigrationConfig    = sys.env.get(migrationConfigEnvVariable).fold(ConfigFactory.empty()) { p =>
      ConfigFactory.parseFile(new File(p), parseOptions)
    }

    val config           = externalMigrationConfig.withFallback(ConfigFactory.load("migration.conf"))
    val batch            = ConfigSource.fromConfig(config).at("migration.batch").loadOrThrow[BatchConfig]
    val ignore           = ConfigSource.fromConfig(config).at("migration.ignore").loadOrThrow[IgnoreConfig]
    val replay           = ReplayConfig.from(config)
    val cassandraSession = CassandraSessionRegistry.get(system).sessionFor(CassandraSessionSettings())
    val replayEvents     = ReplayEvents(cassandraSession, replay)
    val migration        = new Migration(replayEvents, ignore.blacklisted, logs, batch, xas)
    val metadata         = ProjectionMetadata("migration", "migrate-from-cassandra", None, None)
    supervisor
      .run(CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => migration.start))
      .as(migration)
  }

}
