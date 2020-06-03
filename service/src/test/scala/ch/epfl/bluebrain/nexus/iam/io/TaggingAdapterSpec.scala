package ch.epfl.bluebrain.nexus.iam.io

import java.time.Instant

import akka.persistence.journal.Tagged
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent.AclDeleted
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent.PermissionsDeleted
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent.RealmDeprecated
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.Label
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.util.EitherValues
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TaggingAdapterSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues {

  private val pd = PermissionsDeleted(2L, Instant.EPOCH, Anonymous)
  private val ad = AclDeleted(Path("/a/b/c").rightValue, 2L, Instant.EPOCH, Anonymous)
  private val rd = RealmDeprecated(Label.unsafe("blah"), 2L, Instant.EPOCH, Anonymous)

  private val data = Map[AnyRef, (String, AnyRef)](
    pd  -> ("permissions-event" -> Tagged(pd, Set("permissions", "event"))),
    ad  -> ("acl-event"         -> Tagged(ad, Set("acl", "event"))),
    rd  -> ("realm-event"       -> Tagged(rd, Set("realm", "event"))),
    "a" -> (""                  -> "a")
  )

  "A TaggingAdapter" should {
    val adapter = new TaggingAdapter
    "return the correct manifests" in {
      forAll(data.toList) {
        case (event, (manifest, _)) => adapter.manifest(event) shouldEqual manifest
      }
    }
    "return the correct transformed event" in {
      forAll(data.toList) {
        case (event, (_, transformed)) => adapter.toJournal(event) shouldEqual transformed
      }
    }
  }

}
