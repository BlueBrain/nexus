package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{JsonLdDecoder, Configuration => JsonLdConfiguration}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.{Encoder, Json}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import software.amazon.awssdk.regions.Region

import scala.jdk.CollectionConverters._
import java.nio.file.{Path, Paths}
import scala.annotation.nowarn

sealed trait StorageFields extends Product with Serializable { self =>

  type Value <: StorageValue

  /**
    * @return the storage type
    */
  def tpe: StorageType

  /**
    * @return the maximum allowed file size (in bytes) for uploaded files
    */
  def maxFileSize: Option[Long]

  /**
    * @return the permission required in order to download a file to this storage
    */
  def readPermission: Option[Permission]

  /**
    * @return the permission required in order to upload a file to this storage
    */
  def writePermission: Option[Permission]

  /**
    * Converts the current [[StorageFields]] to a [[StorageValue]] resolving some optional values with the passed config
    */
  def toValue(config: StorageTypeConfig): Option[Value]

  /**
    * Returns the decrypted Json representation of the storage fields with the passed @id
    */
  def toJson(iri: Iri): Secret[Json] =
    Secret(self.asJsonObject.add(keywords.id, iri.asJson).asJson)

}

@nowarn("cat=unused")
object StorageFields {

  private def computeMaxFileSize(payloadSize: Option[Long], configMaxFileSize: Long) =
    payloadSize.fold(configMaxFileSize)(size => Math.min(configMaxFileSize, size))

  /**
    * Necessary values to create/update a disk storage
    *
    * @param default         ''true'' if this store is the project's default, ''false'' otherwise
    * @param volume          the volume this storage is going to use to save files
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorageFields(
      default: Boolean,
      volume: Option[Path],
      readPermission: Option[Permission],
      writePermission: Option[Permission],
      maxFileSize: Option[Long]
  ) extends StorageFields {
    override val tpe: StorageType = StorageType.DiskStorage

    override type Value = DiskStorageValue

    override def toValue(config: StorageTypeConfig): Option[Value] =
      Some(
        DiskStorageValue(
          default,
          config.disk.digestAlgorithm,
          volume.getOrElse(config.disk.defaultVolume),
          readPermission.getOrElse(config.disk.defaultReadPermission),
          writePermission.getOrElse(config.disk.defaultWritePermission),
          computeMaxFileSize(maxFileSize, config.disk.defaultMaxFileSize)
        )
      )
  }

  /**
    * Necessary values to create/update a S3 compatible storage
    *
    * @param default         ''true'' if this store is the project's default, ''false'' otherwise
    * @param bucket          the S3 compatible bucket
    * @param endpoint        the endpoint, either a domain or a full URL
    * @param accessKey       the AWS access key ID
    * @param secretKey       the AWS secret key
    * @param region          the AWS region
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class S3StorageFields(
      default: Boolean,
      bucket: String,
      endpoint: Option[Uri],
      accessKey: Option[Secret[String]],
      secretKey: Option[Secret[String]],
      region: Option[Region],
      readPermission: Option[Permission],
      writePermission: Option[Permission],
      maxFileSize: Option[Long]
  ) extends StorageFields {
    override val tpe: StorageType = StorageType.S3Storage

    override type Value = S3StorageValue

    override def toValue(config: StorageTypeConfig): Option[Value] =
      config.amazon.map { cfg =>
        S3StorageValue(
          default,
          cfg.digestAlgorithm,
          bucket,
          endpoint.orElse(cfg.defaultEndpoint),
          accessKey.orElse(if (endpoint.forall(endpoint.contains)) cfg.defaultAccessKey else None),
          secretKey.orElse(if (endpoint.forall(endpoint.contains)) cfg.defaultSecretKey else None),
          region,
          readPermission.getOrElse(cfg.defaultReadPermission),
          writePermission.getOrElse(cfg.defaultWritePermission),
          computeMaxFileSize(maxFileSize, cfg.defaultMaxFileSize)
        )
      }
  }

  /**
    * Necessary values to create/update a Remote disk storage
    *
    * @param default         ''true'' if this store is the project's default, ''false'' otherwise
    * @param endpoint        the endpoint for the remote storage
    * @param credentials     the optional credentials to access the remote storage service
    * @param folder          the rootFolder for this storage
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class RemoteDiskStorageFields(
      default: Boolean,
      endpoint: Option[BaseUri],
      credentials: Option[Secret[String]],
      folder: Label,
      readPermission: Option[Permission],
      writePermission: Option[Permission],
      maxFileSize: Option[Long]
  ) extends StorageFields {

    override val tpe: StorageType = StorageType.RemoteDiskStorage

    override type Value = RemoteDiskStorageValue

    override def toValue(config: StorageTypeConfig): Option[Value] =
      config.remoteDisk.map { cfg =>
        RemoteDiskStorageValue(
          default,
          endpoint = endpoint.getOrElse(cfg.defaultEndpoint),
          credentials =
            credentials.orElse(if (endpoint.forall(_ == cfg.defaultEndpoint)) cfg.defaultCredentials else None),
          folder,
          readPermission.getOrElse(cfg.defaultReadPermission),
          writePermission.getOrElse(cfg.defaultWritePermission),
          computeMaxFileSize(maxFileSize, cfg.defaultMaxFileSize)
        )
      }
  }

  implicit private[model] val storageFieldsEncoder: Encoder.AsObject[StorageFields] = {
    implicit val config: Configuration          = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val pathEncoder: Encoder[Path]     = Encoder.encodeString.contramap(_.toString)
    implicit val regionEncoder: Encoder[Region] = Encoder.encodeString.contramap(_.id())

    // In this case we expose the decrypted string into the json representation, since afterwards it will be encrypted
    implicit val secretStringEncoder: Encoder[Secret[String]] = Encoder.instance(_.value.asJson)

    Encoder.encodeJsonObject.contramapObject { storage =>
      deriveConfiguredEncoder[StorageFields].encodeObject(storage).add(keywords.tpe, storage.tpe.iri.asJson)
    }
  }

  private val regions = Region.regions().asScala

  implicit val storageFieldsJsonLdDecoder: JsonLdDecoder[StorageFields] = {
    val ctx = JsonLdConfiguration.default.context
      .addAlias("DiskStorageFields", StorageType.DiskStorage.iri)
      .addAlias("S3StorageFields", StorageType.S3Storage.iri)
      .addAlias("RemoteDiskStorageFields", StorageType.RemoteDiskStorage.iri)

    implicit val pathJsonLdDecoder: JsonLdDecoder[Path]     = _.getValueTry(Paths.get(_))
    implicit val regionJsonLdDecoder: JsonLdDecoder[Region] =
      _.getValue(s => Option.when(regions.contains(Region.of(s)))(Region.of(s)))

    implicit val config: JsonLdConfiguration = JsonLdConfiguration.default.copy(context = ctx)
    deriveConfigJsonLdDecoder[StorageFields]
  }
}
