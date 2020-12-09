package ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model

import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model.StorageFields._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

class StorageFieldsSpec extends AnyWordSpec with Matchers with TestHelpers with IOValues {

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> jsonContentOf("/contexts/metadata.json"),
    contexts.storage             -> jsonContentOf("/contexts/storages.json")
  )
  implicit private val uuidF: UUIDF                 = UUIDF.random

  "StorageFields" when {

    val project = ProjectGen.project("org", "proj")

    "dealing with disk storages" should {
      val read  = Permission.unsafe("disk/read")
      val write = Permission.unsafe("disk/write")
      val json  = jsonContentOf("storage/disk-storage.json").addContext(contexts.storage)

      "be created from Json-LD" in {
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, json).accepted._2 shouldEqual
          DiskStorageFields(default = true, Paths.get("/tmp"), Some(read), Some(write), Some(50))
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults = json.removeKeys("readPermission", "writePermission", "maxFileSize")
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, jsonNoDefaults).accepted._2 shouldEqual
          DiskStorageFields(default = true, Paths.get("/tmp"), None, None, None)
      }
    }

    "dealing with S3 storages" should {
      val read  = Permission.unsafe("s3/read")
      val write = Permission.unsafe("s3/write")
      val json  = jsonContentOf("storage/s3-storage.json").addContext(contexts.storage)

      "be created from Json-LD" in {
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, json).accepted._2 shouldEqual
          S3StorageFields(
            default = true,
            "mybucket",
            Some("http://localhost"),
            Some("accessKey"),
            Some("secretKey"),
            None,
            Some(read),
            Some(write),
            Some(51)
          )
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys("readPermission", "writePermission", "maxFileSize", "endpoint", "accessKey", "secretKey")
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, jsonNoDefaults).accepted._2 shouldEqual
          S3StorageFields(default = true, "mybucket", None, None, None, None, None, None, None)
      }
    }

    "dealing with remote storages" should {
      val read  = Permission.unsafe("remote/read")
      val write = Permission.unsafe("remote/write")
      val json  = jsonContentOf("storage/remote-storage.json").addContext(contexts.storage)

      "be created from Json-LD" in {
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, json).accepted._2 shouldEqual
          RemoteDiskStorageFields(
            default = true,
            Some("http://localhost"),
            Some(AuthToken.unsafe("authToken")),
            Label.unsafe("myfolder"),
            Some(read),
            Some(write),
            Some(52)
          )
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys("readPermission", "writePermission", "maxFileSize", "endpoint", "credentials")
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, jsonNoDefaults).accepted._2 shouldEqual
          RemoteDiskStorageFields(
            default = true,
            None,
            None,
            Label.unsafe("myfolder"),
            None,
            None,
            None
          )
      }
    }
  }

}
