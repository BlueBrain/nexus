package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsEventSpec extends AnyWordSpecLike with Matchers with TestHelpers with IOValues with CirceLiteral {

  private val resourceContext    = jsonContentOf("contexts/metadata.json")
  private val permissionsContext = jsonContentOf("contexts/permissions.json")

  implicit private val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  implicit private val res: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.metadata -> resourceContext, contexts.permissions -> permissionsContext)

  "A PermissionsAppended" should {
    val event: PermissionsEvent = PermissionsAppended(
      1L,
      Set(acls.read, acls.write),
      Instant.EPOCH,
      Anonymous
    )
    "be serialized correctly to compacted jsonld" in {
      event.toCompactedJsonLd.accepted.json shouldEqual
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
    "be serialized correctly to expanded jsonld" in {
      event.toExpandedJsonLd.accepted.json shouldEqual
        json"""[{
          "https://bluebrain.github.io/nexus/vocabulary/permissionsId" : [
            {
              "@id" : "http://localhost/permissions"
            }
          ],
          "@type" : [
            "https://bluebrain.github.io/nexus/vocabulary/PermissionsAppended"
          ],
          "https://bluebrain.github.io/nexus/vocabulary/instant" : [
            {
              "@value" : "1970-01-01T00:00:00Z"
            }
          ],
          "https://bluebrain.github.io/nexus/vocabulary/rev" : [
            {
              "@value" : 1
            }
          ],
          "https://bluebrain.github.io/nexus/vocabulary/metadata/subject" : [
            {
              "@value" : "http://localhost/anonymous"
            }
          ],
          "https://bluebrain.github.io/nexus/vocabulary/permissions" : [
            {
              "@value" : "acls/read"
            },
            {
              "@value" : "acls/write"
            }
          ]
         }]
         """
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
      event.toCompactedJsonLd.accepted.json shouldEqual
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
    "be serialized to expanded jsonld" in {
      event.toExpandedJsonLd.accepted
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
      event.toCompactedJsonLd.accepted.json shouldEqual
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
    "be serialized to expanded jsonld" in {
      event.toExpandedJsonLd.accepted
    }
  }

  "A PermissionsDeleted" should {
    val event: PermissionsEvent = PermissionsDeleted(
      1L,
      Instant.EPOCH,
      Anonymous
    )
    "be serialized correctly to compacted jsonld" in {
      event.toCompactedJsonLd.accepted.json shouldEqual
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
    "be serialized to expanded jsonld" in {
      event.toExpandedJsonLd.accepted
    }
  }

}
