package ch.epfl.bluebrain.nexus.migration.v1_4.serializer

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Anonymous
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.AclEvent.AclDeleted
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.PermissionsEvent.PermissionsDeleted
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.RealmEvent.RealmDeprecated
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class IamEventSerializerSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable {

  private val pd       = PermissionsDeleted(2L, Instant.EPOCH, Anonymous)
  private val pdString =
    """|{
       |  "rev": 2,
       |  "instant": "1970-01-01T00:00:00Z",
       |  "subject": {
       |    "@id": "http://127.0.0.1:8080/v1/anonymous",
       |    "@type": "Anonymous"
       |  },
       |  "@type": "PermissionsDeleted"
       |}""".stripMargin

  private val ad       = AclDeleted(AclAddress.fromString("/a/b").rightValue, 2L, Instant.EPOCH, Anonymous)
  private val adString =
    """|{
       |  "path": "/a/b",
       |  "rev": 2,
       |  "instant": "1970-01-01T00:00:00Z",
       |  "subject": {
       |    "@id": "http://127.0.0.1:8080/v1/anonymous",
       |    "@type": "Anonymous"
       |  },
       |  "@type": "AclDeleted"
       |}""".stripMargin

  private val rd       = RealmDeprecated(Label.unsafe("blah"), 2L, Instant.EPOCH, Anonymous)
  private val rdString =
    """|{
       |  "id": "blah",
       |  "rev": 2,
       |  "instant": "1970-01-01T00:00:00Z",
       |  "subject": {
       |    "@id": "http://127.0.0.1:8080/v1/anonymous",
       |    "@type": "Anonymous"
       |  },
       |  "@type": "RealmDeprecated"
       |}""".stripMargin

  private val data = Map[AnyRef, (String, String)](
    pd -> ("permissions-event" -> pdString),
    ad -> ("acl-event"         -> adString),
    rd -> ("realm-event"       -> rdString)
  )

  "An EventSerializer" should {
    val serializer = new IamEventSerializer()

    "produce the correct event manifests" in {
      forAll(data.toList) { case (event, (manifest, _)) =>
        serializer.manifest(event) shouldEqual manifest
      }
    }

    "correctly deserialize known events" in {
      forAll(data.toList) { case (event, (manifest, repr)) =>
        serializer.fromBinary(repr.getBytes, manifest) shouldEqual event
      }
    }
  }

}
