package ch.epfl.bluebrain.nexus.migration

import akka.actor.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.InvalidState
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.migration.Migration.{processEvent, MigrationProgress}
import ch.epfl.bluebrain.nexus.migration.config.ReplayConfig
import ch.epfl.bluebrain.nexus.migration.replay.ReplayEvents
import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.typesafe.scalalogging.Logger
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.sqlstate.class22.UNTRANSLATABLE_CHARACTER
import fs2.{Chunk, Stream}
import io.circe.{Json, JsonObject}
import io.circe.optics.JsonPath.root
import io.circe.syntax.EncoderOps
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
            val migrated              = toProcess.traverse(processEvent(logMap))
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

  def processEvent(logMap: Map[EntityType, MigrationLog]): ToMigrateEvent => Task[Unit] = { event =>
    logMap.get(event.entityType) match {
      case Some(migrationLog) =>
        migrationLog(event)
          .onErrorRecoverWith { case e: InvalidState[_, _] =>
            Task.delay {
              logger.warn(
                s"Invalid state encountered while processing event: $event. Proceeding with migration. Original message: ${e.getMessage}"
              )
            }
          }
          .exceptSomeSqlState { case UNTRANSLATABLE_CHARACTER =>
            val cleanPayload = removeFromJson(event.payload, "\u0000")
            Task.delay(logger.warn("Encountered an untranslatable character. Attempting to clean JSON...")) >>
              migrationLog(event.copy(payload = cleanPayload)) >>
              Task.delay(logger.info("... processed clean JSON."))
          }
      case None               =>
        val message = s"The logMap has no entry for ${event.entityType}"
        Task.delay {
          logger.error(message)
        } >>
          Task.raiseError(new NoSuchElementException(message))
    }
  }

  /**
    * Removes the `toRemove` string from all strings in the provided `json`.
    */
  def removeFromJson(json: Json, toRemove: String): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable {
        obj.toMap.view.mapValues(replace)
      }

    def replace(json: Json): Json = json match {
      case j if j.isString => j.asString.get.replace(toRemove, "").asJson
      case j if j.isArray  => removeFromJson(j, toRemove)
      case j if j.isObject => removeFromJson(j, toRemove)
      case j               => j
    }

    json.arrayOrObject(
      replace(json),
      arr => Json.fromValues(arr.map(replace)),
      obj => Json.fromJsonObject(inner(obj))
    )
  }

  /**
    * @param toMigrateEvent
    *   the event that the check is performed upon
    * @param projects
    *   a set of strings defining the ProjectRefs to ignore
    * @return
    *   true if the event is to be ignored, false otherwise
    */
  def toIgnore(toMigrateEvent: ToMigrateEvent, projects: Set[String]): Boolean = {
    val payload = toMigrateEvent.payload
    val project = root.project.string

    // ProjectEvents do not have a project field
    val label                 = root.label.string
    val organizationLabel     = root.organizationLabel.string
    val projectEventCondition =
      label.exist(project => organizationLabel.exist(org => projects.contains(s"$org/$project"))(payload))(payload)

    project.exist(projects.contains)(payload) || projectEventCondition ||
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
