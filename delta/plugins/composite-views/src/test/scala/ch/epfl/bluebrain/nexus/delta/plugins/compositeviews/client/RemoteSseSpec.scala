package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import io.circe.literal._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RemoteSseSpec extends AnyWordSpecLike with Matchers {

  val event =
    json"""
      {
        "@context": [
          "https://bluebrain.github.io/nexus/contexts/metadata.json",
          "https://bluebrain.github.io/nexus/contexts/storages.json"
        ],
        "@type": "StorageCreated",
        "_constrainedBy": "https://bluebrain.github.io/nexus/schemas/storages.json",
        "_instant": "2021-04-29T19:07:01.343Z",
        "_organizationUuid": "3e40224d-60e1-46ed-87fa-4efe72a155c9",
        "_project": "ygfm2884ybtvmrg/songs",
        "_projectUuid": "c6305475-2b24-4cd9-884e-e249d2964856",
        "_resourceId": "https://bluebrain.github.io/nexus/vocabulary/diskStorageDefault",
        "_rev": 1,
        "_source": {
          "@id": "https://bluebrain.github.io/nexus/vocabulary/diskStorageDefault",
          "@type": "https://bluebrain.github.io/nexus/vocabulary/DiskStorage",
          "default": true
        },
        "_storageId": "https://bluebrain.github.io/nexus/vocabulary/diskStorageDefault",
        "_subject": "http://delta:8080/v1/realms/internal/users/delta",
        "_types": [
          "https://bluebrain.github.io/nexus/vocabulary/Storage",
          "https://bluebrain.github.io/nexus/vocabulary/DiskStorage"
        ]
      }
      """

  "A RemoteSse" should {
    "decode an event" in {
      event.as[RemoteSse] match {
        case Left(value) => fail(value.toString())
        case Right(_)    => succeed
      }
    }
  }
}
