package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts, StorageFixtures}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

class StorageFieldsSpec
    extends AnyWordSpec
    with Matchers
    with RemoteContextResolutionFixture
    with IOValues
    with StorageFixtures {

  implicit private val uuidF: UUIDF = UUIDF.random

  "StorageFields" when {

    val project = ProjectGen.project("org", "proj")

    "dealing with disk storages" should {
      val json = diskFieldsJson.value.addContext(contexts.storage)

      "be created from Json-LD" in {
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, json).accepted._2 shouldEqual diskFields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults = json.removeKeys("readPermission", "writePermission", "maxFileSize")
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, jsonNoDefaults).accepted._2 shouldEqual
          DiskStorageFields(default = true, Paths.get("/tmp"), None, None, None)
      }
    }

    "dealing with S3 storages" should {
      val json = s3FieldsJson.value.addContext(contexts.storage)

      "be created from Json-LD" in {
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, json).accepted._2 shouldEqual s3Fields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys("readPermission", "writePermission", "maxFileSize", "endpoint", "accessKey", "secretKey")
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, jsonNoDefaults).accepted._2 shouldEqual
          S3StorageFields(default = true, "mybucket", None, None, None, None, None, None, None)
      }
    }

    "dealing with remote storages" should {
      val json = remoteFieldsJson.value.addContext(contexts.storage)

      "be created from Json-LD" in {
        JsonLdSourceParser.decode[StorageFields, StorageRejection](project, json).accepted._2 shouldEqual remoteFields
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
