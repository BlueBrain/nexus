package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts, StorageFixtures}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StorageFieldsSpec
    extends AnyWordSpec
    with Matchers
    with RemoteContextResolutionFixture
    with IOValues
    with StorageFixtures {

  val sourceDecoder = new JsonLdSourceDecoder[StorageRejection, StorageFields](contexts.storages, UUIDF.random)

  "StorageFields" when {

    val project = ProjectGen.project("org", "proj")

    "dealing with disk storages" should {
      val json = diskFieldsJson.value.addContext(contexts.storages)

      "be created from Json-LD" in {
        sourceDecoder(project, json).accepted._2 shouldEqual diskFields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults = json.removeKeys("readPermission", "writePermission", "maxFileSize", "volume")
        sourceDecoder(project, jsonNoDefaults).accepted._2 shouldEqual
          DiskStorageFields(default = true, None, None, None, None)
      }
    }

    "dealing with S3 storages" should {
      val json = s3FieldsJson.value.addContext(contexts.storages)

      "be created from Json-LD" in {
        sourceDecoder(project, json).accepted._2 shouldEqual s3Fields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys("readPermission", "writePermission", "maxFileSize", "endpoint", "accessKey", "secretKey")
        sourceDecoder(project, jsonNoDefaults).accepted._2 shouldEqual
          S3StorageFields(default = true, "mybucket", None, None, None, None, None, None, None)
      }
    }

    "dealing with remote storages" should {
      val json = remoteFieldsJson.value.addContext(contexts.storages)

      "be created from Json-LD" in {
        sourceDecoder(project, json).accepted._2 shouldEqual remoteFields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys("readPermission", "writePermission", "maxFileSize", "endpoint", "credentials")
        sourceDecoder(project, jsonNoDefaults).accepted._2 shouldEqual
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
