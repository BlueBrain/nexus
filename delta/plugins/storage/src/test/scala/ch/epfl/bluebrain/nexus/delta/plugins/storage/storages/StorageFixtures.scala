package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, EncryptionConfig, RemoteDiskStorageConfig, S3StorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, DigestAlgorithm, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.{DiskStorageFields, RemoteDiskStorageFields, S3StorageFields}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, TestHelpers}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import org.scalatest.OptionValues

import java.nio.file.{Files, Paths}

trait StorageFixtures extends OptionValues with TestHelpers with EitherValuable with CirceLiteral {

  val dId  = nxv + "disk-storage"
  val s3Id = nxv + "s3-storage"
  val rdId = nxv + "remote-disk-storage"

  // format: off
  implicit val config: StorageTypeConfig = StorageTypeConfig(
    encryption = EncryptionConfig(Secret.decrypted("changeme"), Secret.decrypted("salt")),
    disk = DiskStorageConfig(Files.createTempDirectory("disk"), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, 50),
    amazon = Some(S3StorageConfig(DigestAlgorithm.default, Some("localhost"), Some(Secret.decrypted("accessKey")), Some(Secret.decrypted("secretKey")), permissions.read, permissions.write, showLocation = false, 60)),
    remoteDisk = Some(RemoteDiskStorageConfig("localhost", "v1", None, permissions.read, permissions.write, showLocation = false, 70)),
  )
  val crypto: Crypto = config.encryption.crypto

  val diskFields        = DiskStorageFields(default = true, Paths.get("/tmp"), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(50))
  val diskVal           = diskFields.toValue(config).value
  val diskValEncrypted  = diskFields.toValue(config).value.encrypt(crypto).rightValue
  val diskFieldsUpdate  = DiskStorageFields(default = false, Paths.get("/tmp"), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(40))
  val diskValUpdate     = diskFieldsUpdate.toValue(config).value
  val s3Fields          = S3StorageFields(default = true, "mybucket", Some("http://localhost"), Some(Secret.decrypted("accessKey")), Some(Secret.decrypted("secretKey")), None, Some(Permission.unsafe("s3/read")), Some(Permission.unsafe("s3/write")), Some(51))
  val s3Val             = s3Fields.toValue(config).value
  val remoteFields      = RemoteDiskStorageFields(default = true, Some("http://localhost"), Some(Secret.decrypted("authToken")), Label.unsafe("myfolder"), Some(Permission.unsafe("remote/read")), Some(Permission.unsafe("remote/write")), Some(52))
  val remoteVal         = remoteFields.toValue(config).value
  // format: on

  val diskJson   = jsonContentOf("storage/disk-storage.json")
  val s3Json     = jsonContentOf("storage/s3-storage.json")
  val remoteJson = jsonContentOf("storage/remote-storage.json")

  val diskFieldsJson   = Secret.decrypted(diskJson.removeKeys("@id", "@context", "algorithm"))
  val s3FieldsJson     = Secret.decrypted(s3Json.removeKeys("@id", "@context", "algorithm"))
  val remoteFieldsJson = Secret.decrypted(remoteJson.removeKeys("@id", "@context"))

  private val accessKeyEnc   = s3Fields.accessKey.value.flatMapEncrypt(crypto.encrypt).rightValue
  private val secretKeyEnc   = s3Fields.secretKey.value.flatMapEncrypt(crypto.encrypt).rightValue
  private val credentialsEnc = remoteFields.credentials.value.flatMapEncrypt(crypto.encrypt).rightValue

  val diskFieldsEncJson =
    diskFieldsJson.mapEncrypt(identity)

  val s3FieldsEncJson =
    s3FieldsJson.mapEncrypt(
      _ deepMerge json"""{"accessKey": "${accessKeyEnc.value}", "secretKey": "${secretKeyEnc.value}"}"""
    )

  val remoteFieldsEncJson =
    remoteFieldsJson.mapEncrypt(_ deepMerge json"""{"credentials": "${credentialsEnc.value}"}""")

}
