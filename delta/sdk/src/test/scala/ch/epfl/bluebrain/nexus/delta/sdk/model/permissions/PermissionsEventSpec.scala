package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class PermissionsEventSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with CirceLiteral
    with Fixtures {

  implicit private val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  "A PermissionsAppended" should {
    val event: PermissionsEvent = PermissionsAppended(
      1L,
      Set(acls.read, acls.write),
      Instant.EPOCH,
      Anonymous
    )
    "be serialized to json" in {
      event.asJson shouldEqual
        json"""{
          "@context": [
            "https://bluebrain.github.io/nexus/contexts/metadata.json",
            "https://bluebrain.github.io/nexus/contexts/permissions.json"
          ],
          "_permissionsId" : "http://localhost/permissions",
          "@type": "PermissionsAppended",
          "permissions": [ "acls/read", "acls/write" ],
          "_rev": 1,
          "_instant": "1970-01-01T00:00:00Z",
          "_subject": "${baseUri.endpoint}/anonymous"
        }"""
    }
  }

  "A PermissionsSubtracted" should {
    val event: PermissionsEvent = PermissionsSubtracted(
      1L,
      Set(acls.read, acls.write),
      Instant.EPOCH,
      Anonymous
    )
    "be serialized correctly to compacted jsonld" in {
      event.asJson shouldEqual
        json"""{
          "@context": [
            "https://bluebrain.github.io/nexus/contexts/metadata.json",
            "https://bluebrain.github.io/nexus/contexts/permissions.json"
          ],
          "_permissionsId": "http://localhost/permissions",
          "@type": "PermissionsSubtracted",
          "permissions": [ "acls/read", "acls/write" ],
          "_rev": 1,
          "_instant": "1970-01-01T00:00:00Z",
          "_subject": "${baseUri.endpoint}/anonymous"
        }"""
    }
  }

  "A PermissionsReplaced" should {
    val event: PermissionsEvent = PermissionsReplaced(
      1L,
      Set(acls.read, acls.write),
      Instant.EPOCH,
      Anonymous
    )
    "be serialized correctly to compacted jsonld" in {
      event.asJson shouldEqual
        json"""{
          "@context": [
            "https://bluebrain.github.io/nexus/contexts/metadata.json",
            "https://bluebrain.github.io/nexus/contexts/permissions.json"
          ],
          "_permissionsId": "http://localhost/permissions",
          "@type": "PermissionsReplaced",
          "permissions": [ "acls/read", "acls/write" ],
          "_rev": 1,
          "_instant": "1970-01-01T00:00:00Z",
          "_subject": "${baseUri.endpoint}/anonymous"
        }"""
    }
  }

  "A PermissionsDeleted" should {
    val event: PermissionsEvent = PermissionsDeleted(
      1L,
      Instant.EPOCH,
      Anonymous
    )
    "be serialized correctly to compacted jsonld" in {
      event.asJson shouldEqual
        json"""{
          "@context": [
            "https://bluebrain.github.io/nexus/contexts/metadata.json",
            "https://bluebrain.github.io/nexus/contexts/permissions.json"
          ],
          "_permissionsId": "http://localhost/permissions",
          "@type": "PermissionsDeleted",
          "_rev": 1,
          "_instant": "1970-01-01T00:00:00Z",
          "_subject": "${baseUri.endpoint}/anonymous"
        }"""
    }
  }

}
