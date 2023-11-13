package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class StorageSpec extends CatsEffectSpec with RemoteContextResolutionFixture with StorageFixtures {

  "A Storage" should {
    val project       = ProjectRef(Label.unsafe("org"), Label.unsafe("project"))
    val tag           = UserTag.unsafe("tag")
    val diskStorage   =
      DiskStorage(nxv + "disk", project, diskVal, Tags.empty, json"""{"disk": "value"}""")
    val s3Storage     = S3Storage(nxv + "s3", project, s3Val, Tags(tag -> 1), json"""{"s3": "value"}""")
    val remoteStorage =
      RemoteDiskStorage(nxv + "remote", project, remoteVal, Tags.empty, json"""{"remote": "value"}""")

    "be compacted" in {
      forAll(
        List(
          diskStorage   -> diskJson.deepMerge(json"""{"@type": ["Storage", "DiskStorage"]}"""),
          s3Storage     -> s3Json
            .deepMerge(json"""{"@type": ["Storage", "S3Storage"]}""")
            .removeKeys("accessKey", "secretKey"),
          remoteStorage -> remoteJson
            .deepMerge(json"""{"@type": ["Storage", "RemoteDiskStorage"]}""")
            .removeKeys("credentials")
        )
      ) { case (value, compacted) =>
        value.toCompactedJsonLd.accepted.json shouldEqual compacted
      }
    }

    "be expanded" in {
      val diskJson   = jsonContentOf("storages/disk-storage-expanded.json")
      val s3Json     = jsonContentOf("storages/s3-storage-expanded.json")
      val remoteJson = jsonContentOf("storages/remote-storage-expanded.json")

      forAll(List(diskStorage -> diskJson, s3Storage -> s3Json, remoteStorage -> remoteJson)) {
        case (value, expanded) => value.toExpandedJsonLd.accepted.json shouldEqual expanded
      }
    }
  }

}
