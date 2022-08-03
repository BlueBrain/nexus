package ch.epfl.bluebrain.nexus.delta.sdk.organizations.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

import java.time.Instant
import java.util.UUID

class OrganizationSerializationSuite extends SerializationSuite {

  private val sseEncoder = OrganizationEvent.sseEncoder

  val realm: Label        = Label.unsafe("myrealm")
  val subject: Subject    = User("username", realm)
  val org: Label          = Label.unsafe("myorg")
  val orgUuid: UUID       = UUID.fromString("b6bde92f-7836-4da6-8ead-2e0fd516ebe7")
  val description: String = "some description"
  val instant: Instant    = Instant.EPOCH
  val rev                 = 1

  private val orgsEventMapping = Map(
    OrganizationCreated(org, orgUuid, 1, Some(description), instant, subject) -> loadEvents(
      "organizations",
      "org-created.json"
    ),
    OrganizationUpdated(org, orgUuid, 1, Some(description), instant, subject) -> loadEvents(
      "organizations",
      "org-updated.json"
    ),
    OrganizationDeprecated(org, orgUuid, 1, instant, subject)                 -> loadEvents("organizations", "org-deprecated.json")
  )

  orgsEventMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(OrganizationEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(OrganizationEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse.decodeJson(database).assertRight(SseData(ClassUtils.simpleName(event), None, sse))
    }
  }

  private val state = OrganizationState(
    org,
    orgUuid,
    rev = rev,
    deprecated = false,
    description = Some(description),
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("/organizations/org-state.json")

  test(s"Correctly serialize an OrganizationState") {
    assertEquals(OrganizationState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize an OrganizationState") {
    assertEquals(OrganizationState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
