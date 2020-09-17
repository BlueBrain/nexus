package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.Realms.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{GrantType, RealmRejection, WellKnown}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOFixedClock, IOValues}
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RealmsSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with CirceLiteral {

  "The Realm state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val epoch: Instant         = Instant.EPOCH
    val wellKnownUri: Uri      = "https://example.com/auth/realms/myrealm/.well-known/openid-configuration"
    val wellKnown2Uri: Uri     = "https://example.com/auth/realms/myrealm/.well-known/openid-configuration2"
    val authUri: Uri           = "https://example.com/auth/realms/myrealm/protocol/openid-connect/auth"
    val tokenUri: Uri          = "https://example.com/auth/realms/myrealm/protocol/openid-connect/token"
    val token2Uri: Uri         = "https://example.com/auth/realms/myrealm/protocol/openid-connect/token2"
    val uiUri: Uri             = "https://example.com/auth/realms/myrealm/protocol/openid-connect/userinfo"
    val ui2Uri: Uri            = "https://example.com/auth/realms/myrealm/protocol/openid-connect/userinfo2"
    val endUri: Uri            = "https://example.com/auth/realms/myrealm/protocol/openid-connect/logout"
    val issuer: String         = "myrealm"
    val label: Label           = Label.unsafe(issuer)
    val name: Name             = Name.unsafe(issuer)
    val gt: Set[GrantType]     = Set(GrantType.AuthorizationCode, GrantType.Implicit)
    val keys: Set[Json]        = Set(json"""{ "k": "v" }""")
    val wk                     = WellKnown(issuer, gt, keys, authUri, tokenUri, uiUri, None, Some(endUri))
    val wk2                    = WellKnown(issuer, gt, keys, authUri, token2Uri, ui2Uri, None, Some(endUri))
    val wkResolution           = new MockedWellKnow(Map(wellKnownUri -> wk, wellKnown2Uri -> wk2))
    // format: off
    val current                = Current(label, 1L, deprecated = false, name, wellKnownUri, issuer, keys, gt, None, authUri, tokenUri, uiUri, None, Some(endUri), epoch, Anonymous, epoch, Anonymous)
    // format: on
    val subject                = User("myuser", label)
    "evaluating an incoming command" should {

      "create a new event" in {
        // format: off
        evaluate(wkResolution)(Initial, CreateRealm(label, name, wellKnownUri, None, subject)).accepted shouldEqual
          RealmCreated(label, 1L, name, wellKnownUri, issuer, keys, gt, None, authUri, tokenUri, uiUri, None, Some(endUri), epoch, subject)

        evaluate(wkResolution)(current, UpdateRealm(label, 1L, name, wellKnown2Uri, None, subject)).accepted shouldEqual
          RealmUpdated(label, 2L, name, wellKnown2Uri, issuer, keys, gt, None, authUri, token2Uri, ui2Uri, None, Some(endUri), epoch, subject)
        // format: on

        evaluate(wkResolution)(current, DeprecateRealm(label, 1L, subject)).accepted shouldEqual
          RealmDeprecated(label, 2L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          current -> UpdateRealm(label, 2L, name, wellKnownUri, None, subject),
          current -> DeprecateRealm(label, 2L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(wkResolution)(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with RealmAlreadyExists" in {
        evaluate(wkResolution)(current, CreateRealm(label, name, wellKnownUri, None, subject))
          .rejectedWith[RealmAlreadyExists]
      }

      "reject with RealmNotFound" in {
        val list = List(
          Initial -> UpdateRealm(label, 1L, name, wellKnownUri, None, subject),
          Initial -> DeprecateRealm(label, 1L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(wkResolution)(state, cmd).rejectedWith[RealmNotFound]
        }
      }

      "reject with RealmAlreadyDeprecated" in {
        evaluate(wkResolution)(current.copy(deprecated = true), DeprecateRealm(label, 1L, subject))
          .rejectedWith[RealmAlreadyDeprecated]
      }

      "reject with wellKnown resolution error UnsuccessfulOpenIdConfigResponse" in {
        val wellKnownWrongUri: Uri = "https://example.com/auth/realms/myrealmwrong"
        val list                   = List(
          Initial -> CreateRealm(label, name, wellKnownWrongUri, None, subject),
          current -> UpdateRealm(label, 1L, name, wellKnownWrongUri, None, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(wkResolution)(state, cmd).rejectedWith[UnsuccessfulOpenIdConfigResponse]
        }
      }

    }

    "producing next state" should {

      "create a new RealmCreated state" in {
        // format: off
        next(Initial, RealmCreated(label, 1L, name, wellKnownUri, issuer, keys, gt, None, authUri, tokenUri, uiUri, None, Some(endUri), epoch, subject)) shouldEqual
          current.copy(createdBy = subject, updatedBy = subject)

        next(current, RealmCreated(label, 1L, name, wellKnownUri, issuer, keys, gt, None, authUri, tokenUri, uiUri, None, Some(endUri), epoch, subject)) shouldEqual
          current
        // format: on
      }

      "create a new RealmUpdated state" in {
        // format: off
        next(Initial, RealmUpdated(label, 2L, name, wellKnownUri, issuer, keys, gt, None, authUri, tokenUri, uiUri, None, Some(endUri), epoch, subject)) shouldEqual
          Initial

        next(current, RealmUpdated(label, 2L, name, wellKnown2Uri, issuer, keys, gt, None, authUri, token2Uri, ui2Uri, None, Some(endUri), epoch, subject)) shouldEqual
          Current(label, 2L, deprecated = false, name, wellKnown2Uri, issuer, keys, gt, None, authUri, token2Uri, ui2Uri, None, Some(endUri), epoch, Anonymous, epoch, subject)
        // format: on
      }

      "create new RealmDeprecated state" in {
        next(Initial, RealmDeprecated(label, 2L, epoch, subject)) shouldEqual Initial

        next(current, RealmDeprecated(label, 2L, epoch, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedBy = subject)
      }
    }
  }
}

class MockedWellKnow(expected: Map[Uri, WellKnown]) extends WellKnownResolution {

  override def apply(uri: Uri): IO[RealmRejection, WellKnown] =
    expected.get(uri) match {
      case Some(wk) => IO.pure(wk)
      case None     => IO.raiseError(UnsuccessfulOpenIdConfigResponse(uri))
    }
}
