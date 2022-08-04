package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategyConfig, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, RemoteDiskStorageConfig, S3StorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.{DiskStorageFields, RemoteDiskStorageFields, S3StorageFields}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.{Crypto, EncryptionConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import software.amazon.awssdk.regions.Region

import java.nio.file.{Files, Paths}

@SuppressWarnings(Array("OptionGet"))
trait StorageFixtures extends TestHelpers with CirceLiteral {

  val dId  = nxv + "disk-storage"
  val s3Id = nxv + "s3-storage"
  val rdId = nxv + "remote-disk-storage"

  private val diskVolume = AbsolutePath(Files.createTempDirectory("disk")).toOption.get
  private val tmpVolume  = AbsolutePath(Paths.get("/tmp")).toOption.get

  private val httpConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)

  // format: off
  implicit val config: StorageTypeConfig = StorageTypeConfig(
    disk = DiskStorageConfig(diskVolume, Set(diskVolume,tmpVolume), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, Some(5000), 50),
    amazon = Some(S3StorageConfig(DigestAlgorithm.default, Some("localhost"), Some(Secret("accessKey")), Some(Secret("secretKey")), permissions.read, permissions.write, showLocation = false, 60)),
    remoteDisk = Some(RemoteDiskStorageConfig(DigestAlgorithm.default, BaseUri("http://localhost", Label.unsafe("v1")), None, permissions.read, permissions.write, showLocation = false, 70, httpConfig)),
  )
  val crypto: Crypto = EncryptionConfig(Secret("changeme"), Secret("salt")).crypto

  val diskFields        = DiskStorageFields(default = true, Some(tmpVolume), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(1000), Some(50))
  val diskVal           = diskFields.toValue(config).get
  val diskFieldsUpdate  = DiskStorageFields(default = false, Some(tmpVolume), Some(Permission.unsafe("disk/read")), Some(Permission.unsafe("disk/write")), Some(2000), Some(40))
  val diskValUpdate     = diskFieldsUpdate.toValue(config).get
  val s3Fields          = S3StorageFields(default = true, "mybucket", Some("http://localhost"), Some(Secret("accessKey")), Some(Secret("secretKey")), Some(Region.EU_WEST_1), Some(Permission.unsafe("s3/read")), Some(Permission.unsafe("s3/write")), Some(51))
  val s3Val             = s3Fields.toValue(config).get
  val remoteFields      = RemoteDiskStorageFields(default = true, Some(BaseUri.withoutPrefix("http://localhost")), Some(Secret("authToken")), Label.unsafe("myfolder"), Some(Permission.unsafe("remote/read")), Some(Permission.unsafe("remote/write")), Some(52))
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

  val diskFieldsJson   = Secret(diskJson.removeKeys("@id", "@context", "_algorithm"))
  val s3FieldsJson     = Secret(s3Json.removeKeys("@id", "@context", "_algorithm"))
  val remoteFieldsJson = Secret(remoteJson.removeKeys("@id", "@context", "_algorithm"))
}

object StorageFixtures extends StorageFixtures
