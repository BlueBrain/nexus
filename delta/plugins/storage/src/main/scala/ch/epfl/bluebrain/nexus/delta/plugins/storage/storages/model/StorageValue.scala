package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder}
import software.amazon.awssdk.regions.Region

import java.io.File
import java.nio.file.Path
import scala.annotation.nowarn
import scala.reflect.io.Directory
import scala.util.Try

sealed trait StorageValue extends Product with Serializable {

  /**
    * @return
    *   name of the storage
    */
  def name: Option[String]

  /**
    * @return
    *   description of the storage
    */
  def description: Option[String]

  /**
    * @return
    *   the storage type
    */
  def tpe: StorageType

  /**
    * @return
    *   ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

  /**
    * @return
    *   the digest algorithm, e.g. "SHA-256"
    */
  def algorithm: DigestAlgorithm

  /**
    * @return
    *   the maximum allocated capacity for the storage
    */
  def capacity: Option[Long]

  /**
    * @return
    *   the maximum allowed file size (in bytes) for uploaded files
    */
  def maxFileSize: Long

  /**
    * @return
    *   the permission required in order to download a file to this storage
    */
  def readPermission: Permission

  /**
    * @return
    *   the permission required in order to upload a file to this storage
    */
  def writePermission: Permission
}

object StorageValue {

  /**
    * Resolved values to create/update a disk storage
    *
    * @see
    *   [[StorageFields.DiskStorageFields]]
    */
  final case class DiskStorageValue(
      name: Option[String] = None,
      description: Option[String] = None,
      default: Boolean,
      algorithm: DigestAlgorithm,
      volume: AbsolutePath,
      readPermission: Permission,
      writePermission: Permission,
      capacity: Option[Long],
      maxFileSize: Long
  ) extends StorageValue {

    override val tpe: StorageType = StorageType.DiskStorage

    def rootDirectory(project: ProjectRef): Directory =
      new Directory(new File(volume.value.toFile, project.toString))
  }

  object DiskStorageValue {

    /**
      * @return
      *   a DiskStorageValue without name or description
      */
    def apply(
        default: Boolean,
        algorithm: DigestAlgorithm,
        volume: AbsolutePath,
        readPermission: Permission,
        writePermission: Permission,
        capacity: Option[Long],
        maxFileSize: Long
    ): DiskStorageValue =
      DiskStorageValue(None, None, default, algorithm, volume, readPermission, writePermission, capacity, maxFileSize)

  }

  /**
    * Resolved values to create/update a S3 compatible storage
    *
    * @see
    *   [[StorageFields.S3StorageFields]]
    */
  final case class S3StorageValue(
      name: Option[String],
      description: Option[String],
      default: Boolean,
      algorithm: DigestAlgorithm,
      bucket: String,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {

    override val tpe: StorageType       = StorageType.S3Storage
    override val capacity: Option[Long] = None
  }

  object S3StorageValue {

    /**
      * @return
      *   a S3StorageValue without name or description
      */
    def apply(
        default: Boolean,
        algorithm: DigestAlgorithm,
        bucket: String,
        readPermission: Permission,
        writePermission: Permission,
        maxFileSize: Long
    ): S3StorageValue =
      S3StorageValue(
        None,
        None,
        default,
        algorithm,
        bucket,
        readPermission,
        writePermission,
        maxFileSize
      )
  }

  /**
    * Resolved values to create/update a Remote disk storage
    *
    * @see
    *   [[StorageFields.RemoteDiskStorageFields]]
    */
  final case class RemoteDiskStorageValue(
      name: Option[String] = None,
      description: Option[String] = None,
      default: Boolean,
      algorithm: DigestAlgorithm,
      folder: Label,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {

    override val tpe: StorageType       = StorageType.RemoteDiskStorage
    override val capacity: Option[Long] = None
  }

  object RemoteDiskStorageValue {

    /**
      * @return
      *   a RemoteDiskStorageValue without name or description
      */
    def apply(
        default: Boolean,
        algorithm: DigestAlgorithm,
        folder: Label,
        readPermission: Permission,
        writePermission: Permission,
        maxFileSize: Long
    ): RemoteDiskStorageValue =
      RemoteDiskStorageValue(
        None,
        None,
        default,
        algorithm,
        folder,
        readPermission,
        writePermission,
        maxFileSize
      )
  }

  @nowarn("cat=unused")
  implicit private[model] val storageValueEncoder: Encoder.AsObject[StorageValue] = {
    implicit val config: Configuration          = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val regionEncoder: Encoder[Region] = Encoder.encodeString.contramap(_.id())

    Encoder.encodeJsonObject.contramapObject { storage =>
      deriveConfiguredEncoder[StorageValue].encodeObject(storage).add(keywords.tpe, storage.tpe.iri.asJson)
    }
  }

  @SuppressWarnings(Array("TryGet"))
  @nowarn("cat=unused")
  def databaseCodec(implicit configuration: Configuration): Codec.AsObject[StorageValue] = {
    implicit val pathEncoder: Encoder[Path]     = Encoder.encodeString.contramap(_.toString)
    implicit val pathDecoder: Decoder[Path]     = Decoder.decodeString.emapTry(str => Try(Path.of(str)))
    implicit val regionEncoder: Encoder[Region] = Encoder.encodeString.contramap(_.toString)
    implicit val regionDecoder: Decoder[Region] = Decoder.decodeString.map(Region.of)

    implicit val digestCodec: Codec.AsObject[Digest] = deriveConfiguredCodec[Digest]

    deriveConfiguredCodec[StorageValue]
  }

}
