package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.evaluate
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.ce.{CatsEffectSuite, IOFixedClock, IOFromMap}

import java.time.Instant

class RealmsEvaluateSuite extends CatsEffectSuite with IOFromMap with IOFixedClock {

  private val epoch: Instant       = Instant.EPOCH
  private val issuer: String       = "myrealm"
  private val label: Label         = Label.unsafe(issuer)
  private val name: Name           = Name.unsafe(s"$issuer-name")
  private val (wellKnownUri, wk)   = WellKnownGen.create(issuer)
  private val (wellKnown2Uri, wk2) = WellKnownGen.create("myrealm2")
  private val wkResolution         = ioFromMap(
    Map(wellKnownUri -> wk, wellKnown2Uri -> wk2),
    (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
  )

  private val current = RealmGen.state(wellKnownUri, wk, 1)
  private val subject = User("myuser", label)

  private val newOpenId = (_: Label, _: Uri) => IO.unit

  private def openIdAlreadyExists(uri: Uri) =
    (_: Label, _: Uri) => IO.raiseError(RealmOpenIdConfigAlreadyExists(label, uri))

  group("Evaluate a create command") {
    val command = CreateRealm(label, name, wellKnownUri, None, None, subject)

    test("Return the created event") {
      evaluate(wkResolution, newOpenId)(None, command)
        .assertEquals(
          RealmCreated(label, name, wellKnownUri, None, None, wk, epoch, subject)
        )
    }

    test("Fail as openId is already used") {
      evaluate(wkResolution, openIdAlreadyExists(wellKnownUri))(None, command)
        .intercept(RealmOpenIdConfigAlreadyExists(label, wellKnownUri))
    }

    test("Fail as realm already exists") {
      evaluate(wkResolution, newOpenId)(Some(current), command)
        .intercept[RealmAlreadyExists]
    }
  }

  group("Evaluate an update command") {
    val command = UpdateRealm(label, 1, name, wellKnown2Uri, None, None, subject)
    test("Return the updated event") {
      evaluate(wkResolution, newOpenId)(Some(current), command).assertEquals(
        RealmUpdated(label, 2, name, wellKnown2Uri, None, None, wk2, epoch, subject)
      )
    }

    test("Update a realm name") {
      val newName     = Name.unsafe("updatedName")
      val updatedName = command.copy(name = newName)
      evaluate(wkResolution, newOpenId)(Some(current), updatedName).assertEquals(
        RealmUpdated(label, 2, newName, wellKnown2Uri, None, None, wk2, epoch, subject)
      )
    }

    test("Fail as the given openId is already used") {
      evaluate(wkResolution, openIdAlreadyExists(wellKnown2Uri))(
        Some(current),
        command
      ).intercept(RealmOpenIdConfigAlreadyExists(label, wellKnown2Uri))
    }
  }

  group("Deprecating a realm") {

    test("Return the deprecated event") {
      evaluate(wkResolution, newOpenId)(Some(current), DeprecateRealm(label, 1, subject)).assertEquals(
        RealmDeprecated(label, 2, epoch, subject)
      )
    }

    test("Reject with RealmAlreadyDeprecated") {
      evaluate(wkResolution, newOpenId)(
        Some(current.copy(deprecated = true)),
        DeprecateRealm(label, 1, subject)
      ).intercept[RealmAlreadyDeprecated]
    }
  }

  List(
    None -> UpdateRealm(label, 1, name, wellKnownUri, None, None, subject),
    None -> DeprecateRealm(label, 1, subject)
  ).foreach { case (state, cmd) =>
    test(s"for a ${cmd.getClass.getSimpleName} command when the state does not exist") {
      evaluate(wkResolution, (_, _) => IO.unit)(state, cmd).intercept[RealmNotFound]
    }
  }

  group("Fail with an incorrect rev") {
    List(
      current -> UpdateRealm(label, 2, name, wellKnownUri, None, None, subject),
      current -> DeprecateRealm(label, 2, subject)
    ).foreach { case (state, cmd) =>
      test(s"for a ${cmd.getClass.getSimpleName} command with a wrong rev") {
        evaluate(wkResolution, (_, _) => IO.unit)(Some(state), cmd).intercept[IncorrectRev]
      }
    }
  }

  group("Fail with a wellKnown resolution error") {
    val wellKnownWrongUri: Uri = "https://localhost/auth/realms/myrealmwrong"
    List(
      None          -> CreateRealm(label, name, wellKnownWrongUri, None, None, subject),
      Some(current) -> UpdateRealm(label, 1, name, wellKnownWrongUri, None, None, subject)
    ).foreach { case (state, cmd) =>
      test(s"for ${cmd.getClass.getSimpleName} command with a invalid uri") {
        evaluate(wkResolution, newOpenId)(state, cmd).intercept[UnsuccessfulOpenIdConfigResponse]
      }
    }
  }

}
