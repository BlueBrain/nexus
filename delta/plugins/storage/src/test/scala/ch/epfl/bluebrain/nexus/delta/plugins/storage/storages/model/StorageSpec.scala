package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.{Decrypted, Encrypted}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StorageSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with RemoteContextResolutionFixture
    with IOValues
    with StorageFixtures {
  "A Storage" should {
    val project       = ProjectRef(Label.unsafe("org"), Label.unsafe("project"))
    val tag           = Label.unsafe("tag")
    val diskStorage   = DiskStorage(nxv + "disk", project, diskVal, Map.empty, json"""{"disk": "value"}""")
    val s3Storage     = S3Storage(nxv + "s3", project, s3Val, Map(tag -> 1), json"""{"s3": "value"}""")
    val remoteStorage = RemoteDiskStorage(nxv + "remote", project, remoteVal, Map.empty, json"""{"remote": "value"}""")

    "be compacted" in {
      forAll(
        List(
          diskStorage   -> diskJson,
          s3Storage     -> s3Json.removeKeys("accessKey", "secretKey"),
          remoteStorage -> remoteJson.removeKeys("credentials")
        )
      ) { case (value, compacted) =>
        value.toCompactedJsonLd.accepted.json shouldEqual compacted
      }
    }

    "be expanded" in {
      val diskJson   = jsonContentOf("storage/disk-storage-expanded.json")
      val s3Json     = jsonContentOf("storage/s3-storage-expanded.json")
      val remoteJson = jsonContentOf("storage/remote-storage-expanded.json")

      forAll(List(diskStorage -> diskJson, s3Storage -> s3Json, remoteStorage -> remoteJson)) {
        case (value, expanded) => value.toExpandedJsonLd.accepted.json shouldEqual expanded
      }
    }

    "encrypt a DiskStorageValue" in {
      diskVal.encrypt(crypto).rightValue shouldEqual diskVal.copy(encryptionState = Encrypted)
    }

    "decrypt an DiskStorageValue" in {
      val encrypted = diskVal.encrypt(crypto).rightValue
      encrypted.decrypt(crypto).rightValue shouldEqual diskVal
    }

    "encrypt an S3StorageValue" in {
      val encrypted = s3Val.encrypt(crypto).rightValue
      encrypted.accessKey.value should not equal s3Val.accessKey.value
      encrypted.secretKey.value should not equal s3Val.secretKey.value
      encrypted.copy(encryptionState = Decrypted, accessKey = s3Val.accessKey, secretKey = s3Val.secretKey) shouldEqual
        s3Val
    }

    "decrypt an S3StorageValue" in {
      val encrypted = s3Val.encrypt(crypto).rightValue
      encrypted.decrypt(crypto).rightValue shouldEqual s3Val
    }

    "encrypt a RemoteStorageValue" in {
      println(remoteVal.encrypt(crypto))
      val encrypted = remoteVal.encrypt(crypto).rightValue
      encrypted.credentials.value should not equal remoteVal.credentials.value
      encrypted.copy(encryptionState = Decrypted, credentials = remoteVal.credentials) shouldEqual remoteVal
    }

    "decrypt an RemoteStorageValue" in {
      val encrypted = remoteVal.encrypt(crypto).rightValue
      encrypted.decrypt(crypto).rightValue shouldEqual remoteVal
    }
  }

}
