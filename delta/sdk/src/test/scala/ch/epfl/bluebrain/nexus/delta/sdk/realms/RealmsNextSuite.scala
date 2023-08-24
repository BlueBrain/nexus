package ch.epfl.bluebrain.nexus.delta.sdk.realms

import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.next
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmState
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.bio.OptionAssertions

import java.time.Instant

class RealmsNextSuite extends NexusSuite with OptionAssertions {

  private val epoch: Instant       = Instant.EPOCH
  private val time2                = Instant.ofEpochMilli(10L)
  private val issuer: String       = "myrealm"
  private val label: Label         = Label.unsafe(issuer)
  private val name: Name           = Name.unsafe(s"$issuer-name")
  private val (wellKnownUri, wk)   = WellKnownGen.create(issuer)
  private val (wellKnown2Uri, wk2) = WellKnownGen.create("myrealm2")

  private val current = RealmGen.state(wellKnownUri, wk, 1)
  private val subject = User("myuser", label)

  group("Creating a realm") {
    val created = RealmCreated(label, name, wellKnownUri, None, None, wk, time2, subject)
    test("Return a new state when no state exists") {
      val expected = current.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)
      next(None, created).assertSome(expected)
    }
    test("Return none when the event is applied to an existing state") {
      next(Some(current), created).assertNone()
    }
  }

  group("Updating a realm") {
    val updated = RealmUpdated(label, 2, name, wellKnown2Uri, None, None, wk2, time2, subject)
    test("Return an updated state") {
      // format: off
      val expected = RealmState(label, 2, deprecated = false, name, wellKnown2Uri, wk2.issuer, wk2.keys, wk2.grantTypes, None, None, wk2.authorizationEndpoint, wk2.tokenEndpoint, wk2.userInfoEndpoint, wk2.revocationEndpoint, wk2.endSessionEndpoint, epoch, Anonymous, time2, subject)
      // format: on
      next(Some(current), updated).assertSome(expected)
    }

    test("Return none if the state does not exist") {
      next(None, updated).assertNone()
    }
  }

  group("Deprecating a realm") {
    val deprecated = RealmDeprecated(label, 2, time2, subject)
    test("Return a deprecated realm") {
      val expected = current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)
      next(Some(current), deprecated).assertSome(expected)
    }

    test("Return none if the state does not exist") {
      next(None, deprecated).assertNone()
    }
  }

}
