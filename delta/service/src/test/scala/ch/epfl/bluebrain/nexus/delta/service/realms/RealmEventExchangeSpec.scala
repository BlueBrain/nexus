package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent.RealmCreated
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.UnsuccessfulOpenIdConfigResponse
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RealmsDummy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class RealmEventExchangeSpec
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
  val realmLabel                        = Label.unsafe("realm1")
  val realmName                         = Name.unsafe("realm1-name")
  val (realmOpenId, realmWk)            = WellKnownGen.create(realmLabel.value)

  val realms = RealmsDummy(
    ioFromMap(
      Map(
        realmOpenId -> realmWk
      ),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    )
  ).accepted
  realms.create(realmLabel, realmName, realmOpenId, None, None).accepted

  "A RealmsEventExchange" should {

    val tag = UserTag.unsafe("tag")

    val event = RealmCreated(
      realmLabel,
      1L,
      realmName,
      realmOpenId,
      realmWk.issuer,
      realmWk.keys,
      realmWk.grantTypes,
      None,
      None,
      realmWk.authorizationEndpoint,
      realmWk.tokenEndpoint,
      realmWk.userInfoEndpoint,
      realmWk.revocationEndpoint,
      realmWk.endSessionEndpoint,
      Instant.EPOCH,
      subject
    )

    val exchange = new RealmEventExchange(realms)

    "return the latest state from the event" in {

      val result = exchange.toResource(event, None).accepted.value

      result.value.source shouldEqual jsonContentOf("/realms/realm-source.json")
      result.value.resource shouldEqual RealmGen.resourceFor(
        RealmGen.realm(realmOpenId, realmWk),
        1L,
        subject
      )
      result.metadata.value shouldEqual Realm.Metadata(realmLabel)
    }

    "return None at a particular tag" in {
      exchange.toResource(event, Some(tag)).accepted shouldEqual None
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(event).value
      result.value shouldEqual event

      result.encoder(result.value) shouldEqual jsonContentOf("/realms/created-event.json")

    }

  }
}
