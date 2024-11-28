package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, S3StorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.{DiskStorageFields, S3StorageFields}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.ClasspathResources

import java.nio.file.{Files, Paths}

@SuppressWarnings(Array("OptionGet"))
trait StorageFixtures extends CirceLiteral {

  self: ClasspathResources =>

  val dId  = nxv + "disk-storage"
  val s3Id = nxv + "s3-storage"

  private val diskVolume      = AbsolutePath(Files.createTempDirectory("disk")).toOption.get
  val tmpVolume: AbsolutePath = AbsolutePath(Paths.get("/tmp")).toOption.get

  // format: off
  implicit val config: StorageTypeConfig = StorageTypeConfig(
    disk = DiskStorageConfig(diskVolume, Set(diskVolume,tmpVolume), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, 50),
    amazon = Some(S3StorageConfig("localhost", useDefaultCredentialProvider = false, Secret("my_key"), Secret("my_secret_key"),
      permissions.read, permissions.write, showLocation = false, 60, defaultBucket = "potato", prefix = None))
  )
  implicit val showLocation: StoragesConfig.ShowFileLocation = config.showFileLocation
  val diskFields        = DiskStorageFields(Some("diskName"), Some("diskDescription"), default = true, Some(tmpVolume), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(50))
  val diskVal           = diskFields.toValue(config).get
  val diskFieldsUpdate  = DiskStorageFields(Some("diskName"), Some("diskDescription"), default = false, Some(tmpVolume), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(40))
  val diskValUpdate     = diskFieldsUpdate.toValue(config).get
  val s3Fields          = S3StorageFields(Some("s3name"), Some("s3description"), default = true, Some("mybucket"), Some(Permission.unsafe("s3/read")), Some(Permission.unsafe("s3/write")), Some(51))
  val s3Val             = s3Fields.toValue(config).get
  // format: on

  val allowedPerms = Seq(
    diskFields.readPermission.get,
    diskFields.writePermission.get,
    s3Fields.readPermission.get,
    s3Fields.writePermission.get
  )

  val diskJson = jsonContentOf("storages/disk-storage.json")
  val s3Json   = jsonContentOf("storages/s3-storage.json")

  val diskFieldsJson = diskJson.removeKeys("@id", "@context", "_algorithm")
  val s3FieldsJson   = s3Json.removeKeys("@id", "@context", "_algorithm")
}
