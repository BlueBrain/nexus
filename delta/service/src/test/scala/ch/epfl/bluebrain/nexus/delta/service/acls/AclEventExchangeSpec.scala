package ch.epfl.bluebrain.nexus.delta.service.acls

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{AclGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.AclAppended
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, PermissionsDummy, RealmsDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class AclEventExchangeSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  val permissions = PermissionsDummy(Set(Permission.unsafe("resource/read"))).accepted

  val realmLabel             = Label.unsafe("realm1")
  val realmName              = Name.unsafe("realm1-name")
  val (realmOpenId, realmWk) = WellKnownGen.create(realmLabel.value)

  val realms = RealmsDummy(
    ioFromMap(
      Map(
        realmOpenId -> realmWk
      ),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    )
  ).accepted
  realms.create(realmLabel, realmName, realmOpenId, None, None).accepted

  val acls = AclsDummy(permissions, realms).accepted

  val acl = Acl(AclAddress.Root, Identity.Anonymous -> Set(Permission.unsafe("resource/read")))
  acls.append(acl, 0L).accepted

  "An AclEventExchange" should {
    val tag = UserTag.unsafe("tag")

    val event = AclAppended(acl, 1L, Instant.EPOCH, subject)

    val exchange = new AclEventExchange(acls)
    "return the latest state from the event" in {
      val result = exchange.toResource(event, None).accepted.value

      result.value.source shouldEqual jsonContentOf("/acls/acl-source.json")
      result.value.resource shouldEqual AclGen.resourceFor(
        acl,
        1L,
        subject
      )
      result.metadata.value shouldEqual Acl.Metadata(AclAddress.Root)
    }

    "return None at a particular tag" in {
      exchange.toResource(event, Some(tag)).accepted shouldEqual None
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(event).value
      result.value shouldEqual event

      result.encoder(result.value) shouldEqual jsonContentOf("/acls/created-event.json")
    }

  }

}
