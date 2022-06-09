package ch.epfl.bluebrain.nexus.delta.sdk.organizations.model

import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import munit.{Assertions, FunSuite}

import java.time.Instant
import java.util.UUID

class OrganizationEventSuite extends FunSuite with Assertions with TestHelpers {

  val realm: Label        = Label.unsafe("myrealm")
  val subject: Subject    = User("username", realm)
  val org: Label          = Label.unsafe("myorg")
  val orgUuid: UUID       = UUID.fromString("b6bde92f-7836-4da6-8ead-2e0fd516ebe7")
  val description: String = "some description"
  val instant: Instant    = Instant.EPOCH
  val rev                 = 1

  val orgsMapping: Map[OrganizationEvent, Json] = Map(
    OrganizationCreated(org, orgUuid, 1, Some(description), instant, subject) -> jsonContentOf(
      "/organizations/org-created.json"
    ),
    OrganizationUpdated(org, orgUuid, 1, Some(description), instant, subject) -> jsonContentOf(
      "/organizations/org-updated.json"
    ),
    OrganizationDeprecated(org, orgUuid, 1, instant, subject)                 -> jsonContentOf("/organizations/org-deprecated.json")
  )

  orgsMapping.foreach { case (event, json) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(OrganizationEvent.serializer.codec(event), json)
    }
  }

  orgsMapping.foreach { case (event, json) =>
    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(OrganizationEvent.serializer.codec.decodeJson(json), Right(event))
    }
  }

}
