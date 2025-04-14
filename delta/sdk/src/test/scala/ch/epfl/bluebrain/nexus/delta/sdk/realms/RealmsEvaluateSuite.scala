package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.evaluate
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand.*
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent.*
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class RealmsEvaluateSuite extends NexusSuite with IOFromMap {

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

  private val createCommand = CreateRealm(label, name, wellKnownUri, None, None, subject)

  test("Evaluating a create command returns the created event") {
    evaluate(wkResolution, newOpenId, clock)(None, createCommand)
      .assertEquals(
        RealmCreated(label, name, wellKnownUri, None, None, wk, epoch, subject)
      )
  }

  test("Evaluating a create command fails as openId is already used") {
    evaluate(wkResolution, openIdAlreadyExists(wellKnownUri), clock)(None, createCommand)
      .interceptEquals(RealmOpenIdConfigAlreadyExists(label, wellKnownUri))
  }

  test("Evaluating a create command fails as the realm already exists") {
    evaluate(wkResolution, newOpenId, clock)(Some(current), createCommand)
      .intercept[RealmAlreadyExists]
  }

  private val updateCommand = UpdateRealm(label, 1, name, wellKnown2Uri, None, None, subject)

  test("Evaluating an update command returns the updated event") {
    evaluate(wkResolution, newOpenId, clock)(Some(current), updateCommand).assertEquals(
      RealmUpdated(label, 2, name, wellKnown2Uri, None, None, wk2, epoch, subject)
    )
  }

  test("Evaluating an update command modifies the realm name") {
    val newName     = Name.unsafe("updatedName")
    val updatedName = updateCommand.copy(name = newName)
    evaluate(wkResolution, newOpenId, clock)(Some(current), updatedName).assertEquals(
      RealmUpdated(label, 2, newName, wellKnown2Uri, None, None, wk2, epoch, subject)
    )
  }

  test("Evaluating an update command fails as the given openId is already used") {
    evaluate(wkResolution, openIdAlreadyExists(wellKnown2Uri), clock)(
      Some(current),
      updateCommand
    ).interceptEquals(RealmOpenIdConfigAlreadyExists(label, wellKnown2Uri))
  }

  /**
    * Evaluate a deprecate command
    */
  private val deprecateCommand = DeprecateRealm(label, 1, subject)

  test("Evaluating a deprecate command returns the deprecated event") {
    evaluate(wkResolution, newOpenId, clock)(Some(current), deprecateCommand).assertEquals(
      RealmDeprecated(label, 2, epoch, subject)
    )
  }

  test("Evaluating a deprecate command fails with RealmAlreadyDeprecated") {
    val deprecatedState = Some(current.copy(deprecated = true))
    evaluate(wkResolution, newOpenId, clock)(deprecatedState, deprecateCommand).intercept[RealmAlreadyDeprecated]
  }

  List(
    None -> UpdateRealm(label, 1, name, wellKnownUri, None, None, subject),
    None -> DeprecateRealm(label, 1, subject)
  ).foreach { case (state, cmd) =>
    test(s"Evaluating a ${cmd.getClass.getSimpleName} command fails when the state does not exist") {
      evaluate(wkResolution, (_, _) => IO.unit, clock)(state, cmd).intercept[RealmNotFound]
    }
  }

  List(
    current -> UpdateRealm(label, 2, name, wellKnownUri, None, None, subject),
    current -> DeprecateRealm(label, 2, subject)
  ).foreach { case (state, cmd) =>
    test(s"Evaluating a ${cmd.getClass.getSimpleName} command fails with a wrong rev") {
      evaluate(wkResolution, (_, _) => IO.unit, clock)(Some(state), cmd).intercept[IncorrectRev]
    }
  }

  val wellKnownWrongUri: Uri = "https://localhost/auth/realms/myrealmwrong"
  List(
    None          -> CreateRealm(label, name, wellKnownWrongUri, None, None, subject),
    Some(current) -> UpdateRealm(label, 1, name, wellKnownWrongUri, None, None, subject)
  ).foreach { case (state, cmd) =>
    test(s"Evaluating a  ${cmd.getClass.getSimpleName} command fails with a invalid uri") {
      evaluate(wkResolution, newOpenId, clock)(state, cmd).intercept[UnsuccessfulOpenIdConfigResponse]
    }
  }

}
