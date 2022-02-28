package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.migration

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.reconciler.Reconciliation
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.migration.RemoteStorageMigrationImpl.ToMigrateEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageEvent, StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.EncryptionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.migration.RemoteStorageMigration
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.utils.CassandraUtils
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.typesafe.scalalogging.Logger
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Decoder, Encoder, Error, Json, Printer}
import monix.bio.Task
import pureconfig.ConfigSource
import software.amazon.awssdk.regions.Region

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.util.Try

@nowarn("cat=unused")
final class RemoteStorageMigrationImpl private (
    session: CassandraSession,
    config: CassandraConfig,
    as: ActorSystem[Nothing]
) extends RemoteStorageMigration {

  private val reconciliation = new Reconciliation(as)

  private val crypto =
    ConfigSource
      .fromConfig(as.settings.config)
      .at("app")
      .at("encryption")
      .loadOrThrow[EncryptionConfig]
      .crypto

  implicit private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit private val subjectCodec: Codec.AsObject[Subject]   = deriveConfiguredCodec[Subject]
  implicit private val identityCodec: Codec.AsObject[Identity] = deriveConfiguredCodec[Identity]
  implicit private val pathEncoder: Encoder[Path]              = Encoder.encodeString.contramap(_.toString)
  implicit private val pathDecoder: Decoder[Path]              = Decoder.decodeString.emapTry(str => Try(Path.of(str)))
  implicit private val regionEncoder: Encoder[Region]          = Encoder.encodeString.contramap(_.toString)
  implicit private val regionDecoder: Decoder[Region]          = Decoder.decodeString.map(Region.of)

  implicit val jsonSecretEncryptEncoder: Encoder[Secret[Json]] =
    Encoder.encodeJson.contramap(Storage.encryptSourceUnsafe(_, crypto))

  implicit val stringSecretEncryptEncoder: Encoder[Secret[String]] = Encoder.encodeString.contramap {
    case Secret(value) => crypto.encrypt(value).get
  }

  implicit val jsonSecretDecryptDecoder: Decoder[Secret[Json]] =
    Decoder.decodeJson.emap(Storage.decryptSource(_, crypto).toEither.leftMap(_.getMessage))

  implicit val stringSecretEncryptDecoder: Decoder[Secret[String]] =
    Decoder.decodeString.map(str => Secret(crypto.decrypt(str).get))

  implicit private val storageValueCodec: Codec.AsObject[StorageValue] =
    deriveConfiguredCodec[StorageValue]
  implicit private val storageEventCodec: Codec.AsObject[StorageEvent] =
    deriveConfiguredCodec[StorageEvent]

  private val logger: Logger = Logger[RemoteStorageMigrationImpl]

  private val selectStorageEvents =
    s"SELECT persistence_id, event, sequence_nr, timestamp FROM ${config.keyspace}.tag_views where tag_name = ? limit 1000000 allow filtering;"

  private val updateMessage: String =
    s"UPDATE ${config.keyspace}.messages set event = ? where persistence_id = ? and partition_nr = 0 and sequence_nr = ? and timestamp = ?"

  private val deleteTagProgress: String = s"DELETE from ${config.keyspace}.tag_write_progress WHERE persistence_id = ?"

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  def run(newBaseUri: BaseUri): Task[Unit] =
    for {
      _                          <- Task.delay(logger.info("Starting remote storages migration"))
      updateStatement            <- Task.deferFuture(session.prepare(updateMessage))
      deleteTagProgressStatement <- Task.deferFuture(session.prepare(deleteTagProgress))
      // It is ok to get them all as we don't have that many storages
      allEsEvents                <-
        Task
          .deferFuture(
            session.selectAll(selectStorageEvents, Storages.moduleType)
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
      modifiedPIds               <- allEsEvents.traverseFilter(process(newBaseUri, _, updateStatement))
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
      _                          <- Task.delay(logger.info("Migrating remote storages is now completed."))
    } yield ()

  def process(
      newBaseUri: BaseUri,
      toMigrateEvent: ToMigrateEvent,
      updateStatement: PreparedStatement
  ): Task[Option[String]] = {
    Task.delay(logger.info(s"Migrating event for ${toMigrateEvent.persistenceId}")) >>
      Task.fromEither(migrateEvent(newBaseUri, toMigrateEvent.data)).flatMap { newEvent =>
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

  def migrateEvent(newBaseUri: BaseUri, payload: Array[Byte]): Either[Error, Option[StorageEvent]] =
    parse(new String(payload, StandardCharsets.UTF_8)).flatMap {
      _.as[StorageEvent].map {
        case sc: StorageCreated =>
          sc.value match {
            case rd: RemoteDiskStorageValue =>
              Some(sc.copy(value = rd.copy(endpoint = newBaseUri)))
            case _                          => None
          }
        case su: StorageUpdated =>
          su.value match {
            case rd: RemoteDiskStorageValue =>
              Some(su.copy(value = rd.copy(endpoint = newBaseUri)))
            case _                          => None
          }
        case _                  => None
      }
    }

}

object RemoteStorageMigrationImpl {

  final case class ToMigrateEvent(persistenceId: String, data: Array[Byte], sequenceNr: java.lang.Long, timestamp: UUID)

  def apply(as: ActorSystem[Nothing], config: CassandraConfig): Task[RemoteStorageMigration] =
    CassandraUtils.session(as).map { session =>
      new RemoteStorageMigrationImpl(
        session,
        config,
        as
      )
    }

}
