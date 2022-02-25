package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.migration

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.reconciler.Reconciliation
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.migration.MigrationV16ToV17._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.{ElasticSearchViewCreated, ElasticSearchViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ElasticSearchViewType, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.migration.Migration
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.utils.CassandraUtils
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.typesafe.scalalogging.Logger
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.parser._
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Error, Printer}
import monix.bio.Task

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.annotation.nowarn
import scala.concurrent.duration.DurationInt

/**
  * Migrate elasticsearch views to new model with pipeline
  */
final class MigrationV16ToV17 private (
    session: CassandraSession,
    config: CassandraConfig,
    as: ActorSystem[Nothing]
) extends Migration {

  private val selectViewEvents =
    s"SELECT persistence_id, event, sequence_nr, timestamp FROM ${config.keyspace}.tag_views where tag_name = ? limit 1000000 allow filtering;"

  private val updateMessage: String =
    s"UPDATE ${config.keyspace}.messages set event = ? where persistence_id = ? and partition_nr = 0 and sequence_nr = ? and timestamp = ?"

  private val deleteTagProgress: String = s"DELETE from ${config.keyspace}.tag_write_progress WHERE persistence_id = ?"

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  def run: Task[Unit] = {

    val reconciliation = new Reconciliation(as)

    for {
      _                          <- Task.delay(logger.info("Starting elasticsearch views migration"))
      updateStatement            <- Task.deferFuture(session.prepare(updateMessage))
      deleteTagProgressStatement <- Task.deferFuture(session.prepare(deleteTagProgress))
      // It is ok to get them all as we don't have that many views
      allEsEvents                <-
        Task
          .deferFuture(
            session.selectAll(selectViewEvents, ElasticSearchViews.moduleType)
          )
          .map {
            _.map { row =>
              ToMigrateEvent(
                row.getString("persistence_id"),
                row.getByteBuffer("event").array(),
                row.getLong("sequence_nr"),
                row.getUuid("timestamp")
              )
            }
          }
      _                          <- Task.delay(logger.info(s"Migration of ${allEsEvents.size} events"))
      _                          <- Task.delay(logger.info("Updating events"))
      modifiedPIds               <- allEsEvents.traverseFilter(process(_, updateStatement))
      _                          <- Task.delay(logger.info("Deleting tag progress"))
      _                          <- modifiedPIds.traverse { pid =>
                                      Task.deferFuture(session.executeWrite(deleteTagProgressStatement.bind(pid)))
                                    }
      _                          <- Task.sleep(10.seconds)
      _                          <- Task.delay(logger.info("Rebuild tags"))
      _                          <- modifiedPIds.traverse { pid =>
                                      Task.deferFuture(reconciliation.rebuildTagViewForPersistenceIds(pid))
                                    }
      _                          <- Task.delay(logger.info(s"${modifiedPIds.size} events have been modified."))
      _                          <- Task.delay(logger.info("Migrating views is now completed."))
    } yield ()
  }

  def process(toMigrateEvent: ToMigrateEvent, updateStatement: PreparedStatement): Task[Option[String]] =
    Task.delay(logger.info(s"Migrating event for ${toMigrateEvent.persistenceId}")) >>
      Task.fromEither(migrateEvent(toMigrateEvent.data)).flatMap { newEvent =>
        newEvent.fold(Task.none[String]) { event =>
          Task
            .deferFuture(
              session.executeWrite(
                updateStatement.bind(
                  ByteBuffer.wrap(printer.print(event.asJson).getBytes(StandardCharsets.UTF_8)),
                  toMigrateEvent.persistenceId,
                  toMigrateEvent.sequenceNr,
                  toMigrateEvent.timestamp
                )
              )
            )
            .as(Some(toMigrateEvent.persistenceId))
        }
      }
}

@nowarn("cat=unused")
object MigrationV16ToV17 {

  private val logger: Logger = Logger[MigrationV16ToV17]

  final case class ToMigrateEvent(persistenceId: String, data: Array[Byte], sequenceNr: java.lang.Long, timestamp: UUID)

  implicit final private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit final private val subjectEncoder: Encoder.AsObject[Subject]   = deriveConfiguredEncoder[Subject]
  implicit final private val identityEncoder: Encoder.AsObject[Identity] = deriveConfiguredEncoder[Identity]

  implicit private[migration] val elasticSearchViewValueEncoder: Encoder.AsObject[ElasticSearchViewValue] =
    deriveConfiguredEncoder[ElasticSearchViewValue]

  implicit private[migration] val ElasticSearchViewEventEncoder: Encoder.AsObject[ElasticSearchViewEvent] =
    deriveConfiguredEncoder[ElasticSearchViewEvent]

  def apply(as: ActorSystem[Nothing], config: CassandraConfig): Task[Migration] =
    CassandraUtils.session(as).map { session =>
      new MigrationV16ToV17(
        session,
        config,
        as
      )
    }

  def migrateEvent(value: Array[Byte]): Either[Error, Option[ElasticSearchViewEvent]] =
    parse(new String(value, StandardCharsets.UTF_8)).flatMap { json =>
      if (json.hcursor.downField("value").downField("pipeline").succeeded)
        Right(None) // view has already been migrated
      else
        json.as[v16.ElasticSearchViewEvent].map {
          case c: v16.ElasticSearchViewEvent.ElasticSearchViewCreated if c.tpe == ElasticSearchViewType.ElasticSearch =>
            Some(
              ElasticSearchViewCreated(
                c.id,
                c.project,
                c.uuid,
                toNewValue(c.value.asInstanceOf[v16.ElasticSearchViewValue.IndexingElasticSearchViewValue]),
                c.source,
                c.rev,
                c.instant,
                c.subject
              )
            )
          case u: v16.ElasticSearchViewEvent.ElasticSearchViewUpdated if u.tpe == ElasticSearchViewType.ElasticSearch =>
            Some(
              ElasticSearchViewUpdated(
                u.id,
                u.project,
                u.uuid,
                toNewValue(u.value.asInstanceOf[v16.ElasticSearchViewValue.IndexingElasticSearchViewValue]),
                u.source,
                u.rev,
                u.instant,
                u.subject
              )
            )
          case _                                                                                                      => None // nothing to do
        }
    }

  private def toNewValue(old: v16.ElasticSearchViewValue.IndexingElasticSearchViewValue): ElasticSearchViewValue = {
    val pipeline = List(
      !old.includeDeprecated       -> FilterDeprecated(),
      old.resourceTypes.nonEmpty   -> FilterByType(old.resourceTypes),
      old.resourceSchemas.nonEmpty -> FilterBySchema(old.resourceSchemas),
      !old.includeMetadata         -> DiscardMetadata(),
      true                         -> DefaultLabelPredicates(),
      old.sourceAsText             -> SourceAsText()
    ).mapFilter { case (b, p) => Option.when(b)(p) }
    IndexingElasticSearchViewValue(
      resourceTag = old.resourceTag,
      pipeline,
      mapping = old.mapping,
      settings = old.settings,
      context = None,
      permission = old.permission
    )
  }

}
