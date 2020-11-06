package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.Realms.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.WellKnown
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RealmsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with CirceLiteral {

  import RealmsSpec._

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
    val current =  RealmGen.currentState(wellKnownUri, wk, 1L)
    // format: on
    val subject = User("myuser", label)
    "evaluating an incoming command" should {

      val noExistingRealm = IO.pure(Set.empty[RealmResource])

      def existingRealm(l: Label, o: Uri) =
        IO.pure(
          Set(
            RealmGen.resourceFor(
              RealmGen
                .realm(
                  wellKnownUri,
                  wk,
                  None
                )
                .copy(label = l, openIdConfig = o),
              1L,
              subject
            )
          )
        )

      val anotherLabel = Label.unsafe("anotherRealm")

      "reject creation as openId is already used" in {
        evaluate(wkResolution, existingRealm(anotherLabel, wellKnownUri))(
          Initial,
          CreateRealm(label, name, wellKnownUri, None, subject)
        ).rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
          RealmOpenIdConfigAlreadyExists(label, wellKnownUri)
      }

      "create, update and deprecate a realm" in {
        // format: off
        evaluate(wkResolution, existingRealm(anotherLabel, wellKnown2Uri))(
          Initial,
          CreateRealm(label, name, wellKnownUri, None, subject)
        ).accepted shouldEqual
          createEvent(label, name, None, wellKnownUri, wk, epoch, subject)

        evaluate(wkResolution, existingRealm(anotherLabel, wellKnownUri))(
          current,
          UpdateRealm(label, 1L, name, wellKnown2Uri, None, subject)
        ).accepted shouldEqual
          updateEvent(label, 2L, name, None, wellKnown2Uri, wk2, epoch, subject)

        val updatedName = Name.unsafe("updatedName")
        evaluate(wkResolution, existingRealm(label, wellKnown2Uri))(
          current,
          UpdateRealm(label, 1L, updatedName, wellKnown2Uri, None, subject)
        ).accepted shouldEqual
          updateEvent(label, 2L, updatedName, None, wellKnown2Uri, wk2, epoch, subject)
        // format: on

        evaluate(wkResolution, noExistingRealm)(current, DeprecateRealm(label, 1L, subject)).accepted shouldEqual
          RealmDeprecated(label, 2L, epoch, subject)
      }

      "reject update as openId is already used" in {
        evaluate(wkResolution, existingRealm(anotherLabel, wellKnown2Uri))(
          current,
          UpdateRealm(label, 1L, name, wellKnown2Uri, None, subject)
        ).rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
          RealmOpenIdConfigAlreadyExists(label, wellKnown2Uri)
      }

      "reject with IncorrectRev" in {
        val list = List(
          current -> UpdateRealm(label, 2L, name, wellKnownUri, None, subject),
          current -> DeprecateRealm(label, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(wkResolution, noExistingRealm)(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with RealmAlreadyExists" in {
        evaluate(wkResolution, noExistingRealm)(current, CreateRealm(label, name, wellKnownUri, None, subject))
          .rejectedWith[RealmAlreadyExists]
      }

      "reject with RealmNotFound" in {
        val list = List(
          Initial -> UpdateRealm(label, 1L, name, wellKnownUri, None, subject),
          Initial -> DeprecateRealm(label, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(wkResolution, noExistingRealm)(state, cmd).rejectedWith[RealmNotFound]
        }
      }

      "reject with RealmAlreadyDeprecated" in {
        evaluate(wkResolution, noExistingRealm)(current.copy(deprecated = true), DeprecateRealm(label, 1L, subject))
          .rejectedWith[RealmAlreadyDeprecated]
      }

      "reject with wellKnown resolution error UnsuccessfulOpenIdConfigResponse" in {
        val wellKnownWrongUri: Uri = "https://localhost/auth/realms/myrealmwrong"
        val list                   = List(
          Initial -> CreateRealm(label, name, wellKnownWrongUri, None, subject),
          current -> UpdateRealm(label, 1L, name, wellKnownWrongUri, None, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(wkResolution, noExistingRealm)(state, cmd).rejectedWith[UnsuccessfulOpenIdConfigResponse]
        }
      }

    }

    "producing next state" should {

      "create a new RealmCreated state" in {
        val created = createEvent(label, name, None, wellKnownUri, wk, time2, subject)

        next(Initial, created) shouldEqual
          current.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)

        next(current, created) shouldEqual
          current
      }

      "create a new RealmUpdated state" in {
        // format: off
        next(Initial, updateEvent(label, 2L, name, None, wellKnownUri, wk, time2, subject)) shouldEqual
          Initial

        next(current, updateEvent(label, 2L, name, None, wellKnown2Uri, wk2, time2, subject)) shouldEqual
          Current(label, 2L, deprecated = false, name, wellKnown2Uri, wk2.issuer, wk2.keys, wk2.grantTypes, None, wk2.authorizationEndpoint, wk2.tokenEndpoint, wk2.userInfoEndpoint, wk2.revocationEndpoint, wk2.endSessionEndpoint, epoch, Anonymous, time2, subject)
        // format: on
      }

      "create new RealmDeprecated state" in {
        next(Initial, RealmDeprecated(label, 2L, time2, subject)) shouldEqual Initial

        next(current, RealmDeprecated(label, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }

  }
}

object RealmsSpec {

  def createEvent(
      label: Label,
      name: Name,
      logo: Option[Uri],
      openIdConfig: Uri,
      wk: WellKnown,
      instant: Instant,
      subject: Subject
  ): RealmCreated =
    RealmCreated(
      label,
      1L,
      name,
      openIdConfig,
      wk.issuer,
      wk.keys,
      wk.grantTypes,
      logo,
      wk.authorizationEndpoint,
      wk.tokenEndpoint,
      wk.userInfoEndpoint,
      wk.revocationEndpoint,
      wk.endSessionEndpoint,
      instant,
      subject
    )

  def updateEvent(
      label: Label,
      rev: Long,
      name: Name,
      logo: Option[Uri],
      openIdConfig: Uri,
      wk: WellKnown,
      instant: Instant,
      subject: Subject
  ): RealmUpdated =
    RealmUpdated(
      label,
      rev,
      name,
      openIdConfig,
      wk.issuer,
      wk.keys,
      wk.grantTypes,
      logo,
      wk.authorizationEndpoint,
      wk.tokenEndpoint,
      wk.userInfoEndpoint,
      wk.revocationEndpoint,
      wk.endSessionEndpoint,
      instant,
      subject
    )

}
