package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3
import akka.stream.alpakka.s3.{ApiVersion, MemoryBufferType}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState._
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.InvalidEncryptionSecrets
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.Encoder
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.nio.file.Path
import scala.annotation.nowarn

/**
  * A Storage Value computed from the client passed [[StorageFields]] and the applied configuration for fields not
  * passed by the client. A [[StorageValue]] can be encrypted or decrypted
  *
  * @tparam A the encryption status of the [[StorageValue]]
  */
sealed trait StorageValue[A <: EncryptionState] extends Product with Serializable {

  type This[B <: EncryptionState] <: StorageValue[B]

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
    * @return the encryption state for this storage
    */
  def encryptionState: A

  /**
    * Encrypt the current storage
    */
  def encrypt(crypto: Crypto)(implicit ev: A =:= Decrypted): Either[InvalidEncryptionSecrets, This[Encrypted]]

  /**
    * Decrypt the current storage
    */
  def decrypt(crypto: Crypto)(implicit ev: A =:= Encrypted): Either[InvalidEncryptionSecrets, This[Decrypted]]
}

object StorageValue {

  /**
    * Resolved values to create/update a disk storage
    *
    * @see [[StorageFields.DiskStorageFields]]
    */
  final case class DiskStorageValue[A <: EncryptionState](
      default: Boolean,
      algorithm: DigestAlgorithm,
      volume: Path,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      encryptionState: A
  ) extends StorageValue[A] {

    override type This[B <: EncryptionState] = DiskStorageValue[B]

    override val tpe: StorageType = StorageType.DiskStorage

    override def encrypt(
        crypto: Crypto
    )(implicit ev: A =:= Decrypted): Either[InvalidEncryptionSecrets, DiskStorageValue[Encrypted]] =
      Right(copy(encryptionState = Encrypted))

    override def decrypt(
        crypto: Crypto
    )(implicit ev: A =:= Encrypted): Either[InvalidEncryptionSecrets, DiskStorageValue[Decrypted]] =
      Right(copy(encryptionState = Decrypted))

  }

  /**
    * Resolved values to create/update a S3 compatible storage
    *
    * @see [[StorageFields.S3StorageFields]]
    */
  final case class S3StorageValue[A <: EncryptionState](
      default: Boolean,
      algorithm: DigestAlgorithm,
      bucket: String,
      endpoint: Option[Uri],
      accessKey: Option[Secret[A, String]],
      secretKey: Option[Secret[A, String]],
      region: Option[Region],
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      encryptionState: A
  ) extends StorageValue[A] {

    override val tpe: StorageType = StorageType.S3Storage

    override type This[B <: EncryptionState] = S3StorageValue[B]

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

    /**
      * Encrypt the accessKey and secretKey of a decrypted [[S3StorageValue]]
      */
    override def encrypt(
        crypto: Crypto
    )(implicit ev: A =:= Decrypted): Either[InvalidEncryptionSecrets, S3StorageValue[Encrypted]] =
      (accessKey.traverse(_.flatMapEncrypt(crypto.encrypt)), secretKey.traverse(_.flatMapEncrypt(crypto.encrypt)))
        .mapN { case (encryptedAccessKey, encryptedSecret) =>
          copy(accessKey = encryptedAccessKey, secretKey = encryptedSecret, encryptionState = Encrypted)
        }
        .leftMap(InvalidEncryptionSecrets(tpe, _))

    /**
      * Decrypt the accessKey and secretKey of an encrypted [[S3StorageValue]]
      */
    override def decrypt(
        crypto: Crypto
    )(implicit ev: A =:= Encrypted): Either[InvalidEncryptionSecrets, S3StorageValue[Decrypted]] =
      (accessKey.traverse(_.flatMapDecrypt(crypto.decrypt)), secretKey.traverse(_.flatMapDecrypt(crypto.decrypt)))
        .mapN { case (decryptedAccessKey, decryptedSecret) =>
          copy(accessKey = decryptedAccessKey, secretKey = decryptedSecret, encryptionState = Decrypted)
        }
        .leftMap(InvalidEncryptionSecrets(tpe, _))
  }

  /**
    * Resolved values to create/update a Remote disk storage
    *
    * @see [[StorageFields.RemoteDiskStorageFields]]
    */
  final case class RemoteDiskStorageValue[A <: EncryptionState](
      default: Boolean,
      endpoint: Uri,
      credentials: Option[Secret[A, String]],
      folder: Label,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      encryptionState: A
  ) extends StorageValue[A] {
    override val tpe: StorageType = StorageType.RemoteDiskStorage

    override type This[B <: EncryptionState] = RemoteDiskStorageValue[B]

    /**
      * Encrypt the credentials of a decrypted [[RemoteDiskStorageValue]]
      */
    override def encrypt(
        crypto: Crypto
    )(implicit ev: A =:= Decrypted): Either[InvalidEncryptionSecrets, RemoteDiskStorageValue[Encrypted]] =
      credentials
        .traverse(_.flatMapEncrypt(crypto.encrypt))
        .map(encrypted => copy(credentials = encrypted, encryptionState = Encrypted))
        .leftMap(InvalidEncryptionSecrets(tpe, _))

    /**
      * Decrypt the credentials of an encrypted [[RemoteDiskStorageValue]]
      */
    override def decrypt(
        crypto: Crypto
    )(implicit ev: A =:= Encrypted): Either[InvalidEncryptionSecrets, RemoteDiskStorageValue[Decrypted]] =
      credentials
        .traverse(_.flatMapDecrypt(crypto.decrypt))
        .map(decrypted => copy(credentials = decrypted, encryptionState = Decrypted))
        .leftMap(InvalidEncryptionSecrets(tpe, _))
  }

  @nowarn("cat=unused")
  implicit private[model] val storageValueEncoder: Encoder[StorageValue[Decrypted]] = {
    implicit val config: Configuration          = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val pathEncoder: Encoder[Path]     = Encoder.encodeString.contramap(_.toString)
    implicit val regionEncoder: Encoder[Region] = Encoder.encodeString.contramap(_.id())

    Encoder.encodeJsonObject.contramapObject { storage =>
      deriveConfiguredEncoder[StorageValue[Decrypted]].encodeObject(storage).add(keywords.tpe, storage.tpe.iri.asJson)
    }
  }
}
