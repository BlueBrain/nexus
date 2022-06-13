package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import io.circe.Encoder
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class PermissionsEventSseSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with CirceLiteral
    with Fixtures {

  private val baseUri: BaseUri                             = BaseUri.withoutPrefix("http://localhost")
  implicit val encoder: Encoder.AsObject[PermissionsEvent] = PermissionsEvent.sseEncoder(baseUri)

  "A PermissionsAppended" should {
    val event: PermissionsEvent = PermissionsAppended(
      1,
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
      1,
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
      1,
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
      1,
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
