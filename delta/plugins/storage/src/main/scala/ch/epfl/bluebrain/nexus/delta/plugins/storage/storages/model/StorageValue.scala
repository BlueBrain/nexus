package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.Uri
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

import java.nio.file.Path
import scala.annotation.nowarn

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
      accessKey: Option[String],
      secretKey: Option[String],
      region: Option[String],
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      encryptionState: A
  ) extends StorageValue[A] {

    override val tpe: StorageType = StorageType.S3Storage

    override type This[B <: EncryptionState] = S3StorageValue[B]

    /**
      * Encrypt the accessKey and secretKey of a decrypted [[S3StorageValue]]
      */
    override def encrypt(
        crypto: Crypto
    )(implicit ev: A =:= Decrypted): Either[InvalidEncryptionSecrets, S3StorageValue[Encrypted]] =
      (accessKey.traverse(crypto.encrypt), secretKey.traverse(crypto.encrypt))
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
      (accessKey.traverse(crypto.decrypt), secretKey.traverse(crypto.decrypt))
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
      credentials: Option[String],
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
        .traverse(crypto.encrypt)
        .map { encryptedCredentials =>
          copy(credentials = encryptedCredentials, encryptionState = Encrypted)
        }
        .leftMap(InvalidEncryptionSecrets(tpe, _))

    /**
      * Decrypt the credentials of an encrypted [[RemoteDiskStorageValue]]
      */
    override def decrypt(
        crypto: Crypto
    )(implicit ev: A =:= Encrypted): Either[InvalidEncryptionSecrets, RemoteDiskStorageValue[Decrypted]] =
      credentials
        .traverse(crypto.decrypt)
        .map { encryptedCredentials =>
          copy(credentials = encryptedCredentials, encryptionState = Decrypted)
        }
        .leftMap(InvalidEncryptionSecrets(tpe, _))
  }

  @nowarn("cat=unused")
  implicit private[model] val storageValueEncoder: Encoder[StorageValue[Decrypted]] = {
    implicit val config: Configuration      = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap(_.toString)

    Encoder.encodeJsonObject.contramapObject { storage =>
      deriveConfiguredEncoder[StorageValue[Decrypted]]
        .encodeObject(storage)
        .add(keywords.tpe, storage.tpe.iri.asJson)
        .remove("credentials")
        .remove("accessKey")
        .remove("secretKey")
    }
  }
}
