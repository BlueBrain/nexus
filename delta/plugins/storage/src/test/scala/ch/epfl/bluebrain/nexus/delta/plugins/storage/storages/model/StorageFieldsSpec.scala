package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts, StorageDecoderConfiguration, StorageFixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class StorageFieldsSpec extends CatsEffectSpec with RemoteContextResolutionFixture with StorageFixtures {

  implicit private val cfg: Configuration = StorageDecoderConfiguration.apply.accepted
  val sourceDecoder                       = new JsonLdSourceDecoder[StorageFields](contexts.storages, UUIDF.random)

  "StorageFields" when {
    val pc = ProjectContext.unsafe(ApiMappings.empty, nxv.base, nxv.base, enforceSchema = false)

    "dealing with disk storages" should {
      val json = diskJson.addContext(contexts.storages)

      "be created from Json-LD" in {
        sourceDecoder(pc, json).accepted._2 shouldEqual diskFields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults = json.removeKeys(
          "name",
          "description",
          "readPermission",
          "writePermission",
          "maxFileSize",
          "volume"
        )
        sourceDecoder(pc, jsonNoDefaults).accepted._2 shouldEqual
          DiskStorageFields(None, None, default = true, None, None, None, None)
      }
    }

    "dealing with S3 storages" should {
      val json = s3FieldsJson.addContext(contexts.storages)

      "be created from Json-LD" in {
        sourceDecoder(pc, json).accepted._2 shouldEqual s3Fields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys(
            "name",
            "description",
            "readPermission",
            "writePermission",
            "maxFileSize",
            "endpoint",
            "accessKey",
            "secretKey",
            "region"
          )
        sourceDecoder(pc, jsonNoDefaults).accepted._2 shouldEqual
          S3StorageFields(None, None, default = true, "mybucket", None, None, None)
      }
    }

    "dealing with remote storages" should {
      val json = remoteFieldsJson.addContext(contexts.storages)

      "be created from Json-LD" in {
        sourceDecoder(pc, json).accepted._2 shouldEqual remoteFields
      }

      "be created from Json-LD without optional values" in {
        val jsonNoDefaults =
          json.removeKeys(
            "name",
            "description",
            "readPermission",
            "writePermission",
            "maxFileSize",
            "endpoint",
            "credentials"
          )
        sourceDecoder(pc, jsonNoDefaults).accepted._2 shouldEqual
          RemoteDiskStorageFields(None, None, default = true, Label.unsafe("myfolder"), None, None, None)
      }
    }
  }

}
