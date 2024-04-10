package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, RemoteDiskStorageConfig, S3StorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.{DiskStorageFields, RemoteDiskStorageFields, S3StorageFields}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ClasspathResources

import java.nio.file.{Files, Paths}
import scala.concurrent.duration._

@SuppressWarnings(Array("OptionGet"))
trait StorageFixtures extends CirceLiteral {

  self: ClasspathResources =>

  val dId  = nxv + "disk-storage"
  val s3Id = nxv + "s3-storage"
  val rdId = nxv + "remote-disk-storage"

  private val diskVolume = AbsolutePath(Files.createTempDirectory("disk")).toOption.get
  private val tmpVolume  = AbsolutePath(Paths.get("/tmp")).toOption.get

  // format: off
  implicit val config: StorageTypeConfig = StorageTypeConfig(
    disk = DiskStorageConfig(diskVolume, Set(diskVolume,tmpVolume), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, Some(5000), 50),
    amazon = Some(S3StorageConfig(DigestAlgorithm.default, "localhost", Secret(MinioDocker.RootUser), Secret(MinioDocker.RootPassword),
      permissions.read, permissions.write, showLocation = false, 60)),
    remoteDisk = Some(RemoteDiskStorageConfig(DigestAlgorithm.default, BaseUri("http://localhost", Label.unsafe("v1")), Anonymous, permissions.read, permissions.write, showLocation = false, 70, 50.millis)),
  )
  implicit val showLocation: StoragesConfig.ShowFileLocation = config.showFileLocation
  val diskFields        = DiskStorageFields(Some("diskName"), Some("diskDescription"), default = true, Some(tmpVolume), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(1000), Some(50))
  val diskVal           = diskFields.toValue(config).get
  val diskFieldsUpdate  = DiskStorageFields(Some("diskName"), Some("diskDescription"), default = false, Some(tmpVolume), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(2000), Some(40))
  val diskValUpdate     = diskFieldsUpdate.toValue(config).get
  val s3Fields          = S3StorageFields(Some("s3name"), Some("s3description"), default = true, "mybucket", Some(Permission.unsafe("s3/read")), Some(Permission.unsafe("s3/write")), Some(51))
  val s3Val             = s3Fields.toValue(config).get
  val remoteFields      = RemoteDiskStorageFields(Some("remoteName"), Some("remoteDescription"), default = true, Label.unsafe("myfolder"), Some(Permission.unsafe("remote/read")), Some(Permission.unsafe("remote/write")), Some(52))
  val remoteVal         = remoteFields.toValue(config).get
  // format: on

  val allowedPerms = Seq(
    diskFields.readPermission.get,
    diskFields.writePermission.get,
    s3Fields.readPermission.get,
    s3Fields.writePermission.get,
    remoteFields.readPermission.get,
    remoteFields.writePermission.get
  )

  val diskJson   = jsonContentOf("storages/disk-storage.json")
  val s3Json     = jsonContentOf("storages/s3-storage.json")
  val remoteJson = jsonContentOf("storages/remote-storage.json")

  val diskFieldsJson   = diskJson.removeKeys("@id", "@context", "_algorithm")
  val s3FieldsJson     = s3Json.removeKeys("@id", "@context", "_algorithm")
  val remoteFieldsJson = remoteJson.removeKeys("@id", "@context", "_algorithm")
}
