package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{IncorrectRev, RealmAlreadyDeprecated, RealmAlreadyExists, RealmNotFound, RealmOpenIdConfigAlreadyExists, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmState
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class RealmsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with OptionValues
    with Inspectors
    with IOFixedClock
    with IOValues
    with CirceLiteral {

  "The Realm state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val epoch: Instant         = Instant.EPOCH
    val time2                  = Instant.ofEpochMilli(10L)
    val issuer: String         = "myrealm"
    val label: Label           = Label.unsafe(issuer)
    val name: Name             = Name.unsafe(s"$issuer-name")
    val (wellKnownUri, wk)     = WellKnownGen.create(issuer)
    val (wellKnown2Uri, wk2)   = WellKnownGen.create("myrealm2")
    val wkResolution           = ioFromMap(
      Map(wellKnownUri -> wk, wellKnown2Uri -> wk2),
      (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
    )

    // format: off
    val current =  RealmGen.state(wellKnownUri, wk, 1)
    // format: on
    val subject = User("myuser", label)
    "evaluating an incoming command" should {

      "reject creation as openId is already used" in {
        evaluate(wkResolution, (_, _) => IO.raiseError(RealmOpenIdConfigAlreadyExists(label, wellKnownUri)))(
          None,
          CreateRealm(label, name, wellKnownUri, None, None, subject)
        ).rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
          RealmOpenIdConfigAlreadyExists(label, wellKnownUri)
      }

      "create a realm" in {
        evaluate(wkResolution, (_, _) => IO.unit)(
          None,
          CreateRealm(label, name, wellKnownUri, None, None, subject)
        ).accepted shouldEqual
          RealmCreated(label, name, wellKnownUri, None, None, wk, epoch, subject)
      }

      "update a realm" in {
        evaluate(wkResolution, (_, _) => IO.unit)(
          Some(current),
          UpdateRealm(label, 1, name, wellKnown2Uri, None, None, subject)
        ).accepted shouldEqual
          RealmUpdated(label, 2, name, wellKnown2Uri, None, None, wk2, epoch, subject)
        val updatedName = Name.unsafe("updatedName")
        evaluate(wkResolution, (_, _) => IO.unit)(
          Some(current),
          UpdateRealm(label, 1, updatedName, wellKnown2Uri, None, None, subject)
        ).accepted shouldEqual
          RealmUpdated(label, 2, updatedName, wellKnown2Uri, None, None, wk2, epoch, subject)
      }

      "deprecate a realm" in {
        evaluate(wkResolution, (_, _) => IO.unit)(Some(current), DeprecateRealm(label, 1, subject)).accepted shouldEqual
          RealmDeprecated(label, 2, epoch, subject)
      }

      "reject update as openId is already used" in {
        evaluate(wkResolution, (_, _) => IO.raiseError(RealmOpenIdConfigAlreadyExists(label, wellKnown2Uri)))(
          Some(current),
          UpdateRealm(label, 1, name, wellKnown2Uri, None, None, subject)
        ).rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
          RealmOpenIdConfigAlreadyExists(label, wellKnown2Uri)
      }

      "reject with IncorrectRev" in {
        val list = List(
          current -> UpdateRealm(label, 2, name, wellKnownUri, None, None, subject),
          current -> DeprecateRealm(label, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(wkResolution, (_, _) => IO.unit)(Some(state), cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with RealmAlreadyExists" in {
        evaluate(wkResolution, (_, _) => IO.unit)(
          Some(current),
          CreateRealm(label, name, wellKnownUri, None, None, subject)
        )
          .rejectedWith[RealmAlreadyExists]
      }

      "reject with RealmNotFound" in {
        val list = List(
          None -> UpdateRealm(label, 1, name, wellKnownUri, None, None, subject),
          None -> DeprecateRealm(label, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(wkResolution, (_, _) => IO.unit)(state, cmd).rejectedWith[RealmNotFound]
        }
      }

      "reject with RealmAlreadyDeprecated" in {
        evaluate(wkResolution, (_, _) => IO.unit)(
          Some(current.copy(deprecated = true)),
          DeprecateRealm(label, 1, subject)
        )
          .rejectedWith[RealmAlreadyDeprecated]
      }

      "reject with wellKnown resolution error UnsuccessfulOpenIdConfigResponse" in {
        val wellKnownWrongUri: Uri = "https://localhost/auth/realms/myrealmwrong"
        val list                   = List(
          None          -> CreateRealm(label, name, wellKnownWrongUri, None, None, subject),
          Some(current) -> UpdateRealm(label, 1, name, wellKnownWrongUri, None, None, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(wkResolution, (_, _) => IO.unit)(state, cmd).rejectedWith[UnsuccessfulOpenIdConfigResponse]
        }
      }

    }

    "producing next state" should {

      "create a new state or return none when the event is applied to an existing state" in {
        val created = RealmCreated(label, name, wellKnownUri, None, None, wk, time2, subject)

        next(None, created).value shouldEqual
          current.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)

        next(Some(current), created) shouldEqual None
      }

      "update the current state or return none if it does not exist" in {
        // format: off
        next(Some(current), RealmUpdated(label, 2, name, wellKnown2Uri, None,None, wk2, time2, subject)).value shouldEqual
          RealmState(label, 2, deprecated = false, name, wellKnown2Uri, wk2.issuer, wk2.keys, wk2.grantTypes, None,None, wk2.authorizationEndpoint, wk2.tokenEndpoint, wk2.userInfoEndpoint, wk2.revocationEndpoint, wk2.endSessionEndpoint, epoch, Anonymous, time2, subject)

        next(None, RealmUpdated(label, 2, name, wellKnownUri, None,None, wk, time2, subject)) shouldEqual
          None
        // format: on
      }

      "deprecate the realm or return none if it does not exist" in {
        next(Some(current), RealmDeprecated(label, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)

        next(None, RealmDeprecated(label, 2, time2, subject)) shouldEqual None
      }
    }
  }
}
