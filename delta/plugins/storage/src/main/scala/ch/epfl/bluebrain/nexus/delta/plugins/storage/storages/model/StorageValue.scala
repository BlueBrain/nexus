package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3
import akka.stream.alpakka.s3.{ApiVersion, MemoryBufferType}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.nio.file.Path
import scala.annotation.nowarn

sealed trait StorageValue extends Product with Serializable {

  /**
    * @return the storage type
    */
  def tpe: StorageType

  /**
    * @return ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

  /**
    * @return the maximum allowed file size (in bytes) for uploaded files
    */
  def maxFileSize: Long

  /**
    * @return a set of secrets for the current storage
    */
  def secrets: Set[Secret[String]]

  /**
    * @return the permission required in order to download a file to this storage
    */
  def readPermission: Permission

  /**
    * @return the permission required in order to upload a file to this storage
    */
  def writePermission: Permission
}

object StorageValue {

  /**
    * Resolved values to create/update a disk storage
    *
    * @see [[StorageFields.DiskStorageFields]]
    */
  final case class DiskStorageValue(
      default: Boolean,
      algorithm: DigestAlgorithm,
      volume: Path,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {

    override val tpe: StorageType             = StorageType.DiskStorage
    override val secrets: Set[Secret[String]] = Set.empty
  }

  /**
    * Resolved values to create/update a S3 compatible storage
    *
    * @see [[StorageFields.S3StorageFields]]
    */
  final case class S3StorageValue(
      default: Boolean,
      algorithm: DigestAlgorithm,
      bucket: String,
      endpoint: Option[Uri],
      accessKey: Option[Secret[String]],
      secretKey: Option[Secret[String]],
      region: Option[Region],
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {

    override val tpe: StorageType             = StorageType.S3Storage
    override val secrets: Set[Secret[String]] = Set.empty ++ accessKey ++ secretKey

    private def address(bucket: String): Uri =
      endpoint match {
        case Some(host) if host.scheme.trim.isEmpty => Uri(s"https://$bucket.$host")
        case Some(e)                                => e.withHost(s"$bucket.${e.authority.host}")
        case None                                   => region.fold(s"https://$bucket.s3.amazonaws.com")(r => s"https://$bucket.s3.$r.amazonaws.com")
      }

    /**
      * @return these settings converted to an instance of [[akka.stream.alpakka.s3.S3Settings]]
      */
    def toAlpakkaSettings: s3.S3Settings = {
      val credsProvider = (accessKey, secretKey) match {
        case (Some(accessKey), Some(secretKey)) =>
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.value, secretKey.value))
        case _                                  =>
          StaticCredentialsProvider.create(AnonymousCredentialsProvider.create().resolveCredentials())
      }

      val regionProvider: AwsRegionProvider = new AwsRegionProvider {
        val getRegion: Region = region.getOrElse {
          endpoint match {
            case None                                                                 => Region.US_EAST_1
            case Some(uri) if uri.authority.host.toString().contains("amazonaws.com") => Region.US_EAST_1
            case _                                                                    => Region.AWS_GLOBAL
          }
        }
      }

      s3.S3Settings(MemoryBufferType, credsProvider, regionProvider, ApiVersion.ListBucketVersion2)
        .withEndpointUrl(address(bucket).toString())
    }
  }

  /**
    * Resolved values to create/update a Remote disk storage
    *
    * @see [[StorageFields.RemoteDiskStorageFields]]
    */
  final case class RemoteDiskStorageValue(
      default: Boolean,
      endpoint: BaseUri,
      credentials: Option[Secret[String]],
      folder: Label,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType             = StorageType.RemoteDiskStorage
    override val secrets: Set[Secret[String]] = Set.empty ++ credentials
  }

  @nowarn("cat=unused")
  implicit private[model] val storageValueEncoder: Encoder.AsObject[StorageValue] = {
    implicit val config: Configuration          = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val pathEncoder: Encoder[Path]     = Encoder.encodeString.contramap(_.toString)
    implicit val regionEncoder: Encoder[Region] = Encoder.encodeString.contramap(_.id())

    Encoder.encodeJsonObject.contramapObject { storage =>
      deriveConfiguredEncoder[StorageValue].encodeObject(storage).add(keywords.tpe, storage.tpe.iri.asJson)
    }
  }
}
