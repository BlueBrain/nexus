package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUndeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.JsonObject

import java.time.Instant
import java.util.UUID

class ProjectSerializationSuite extends SerializationSuite {

  private val sseEncoder = ProjectEvent.sseEncoder

  private val instant: Instant = Instant.EPOCH
  private val rev: Int         = 1

  private val realm: Label     = Label.unsafe("myrealm")
  private val subject: Subject = User("username", realm)

  private val org: Label          = Label.unsafe("myorg")
  private val orgUuid: UUID       = UUID.fromString("b6bde92f-7836-4da6-8ead-2e0fd516ebe7")
  private val description: String = "some description"

  private val proj: Label              = Label.unsafe("myproj")
  private val projUuid: UUID           = UUID.fromString("fe1301a6-a105-4966-84af-32723fd003d2")
  private val apiMappings: ApiMappings = ApiMappings("nxv" -> nxv.base)
  private val base: PrefixIri          = PrefixIri.unsafe(schemas.base)
  private val vocab: PrefixIri         = PrefixIri.unsafe(nxv.base)

  private val created    =
    ProjectCreated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      description = Some(description),
      apiMappings = apiMappings,
      base = base,
      vocab = vocab,
      enforceSchema = true,
      instant = instant,
      subject = subject
    )
  private val updated    =
    ProjectUpdated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      description = Some(description),
      apiMappings = apiMappings,
      base = base,
      vocab = vocab,
      enforceSchema = true,
      instant = instant,
      subject = subject
    )
  private val deprecated =
    ProjectDeprecated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      instant = instant,
      subject = subject
    )

  private val undeprecated =
    ProjectUndeprecated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      instant = instant,
      subject = subject
    )

  private val projectsMapping = List(
    (created, loadEvents("projects", "project-created.json"), Created),
    (updated, loadEvents("projects", "project-updated.json"), Updated),
    (deprecated, loadEvents("projects", "project-deprecated.json"), Deprecated),
    (undeprecated, loadEvents("projects", "project-undeprecated.json"), Undeprecated)
  )

  projectsMapping.foreach { case (event, (database, sse), action) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertOutput(ProjectEvent.serializer, event, database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(ProjectEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(ProjectRef(org, proj)), sse))
    }

    test(s"Correctly encode ${event.getClass.getName} to metric") {
      ProjectEvent.projectEventMetricEncoder.toMetric.decodeJson(database).assertRight {
        ProjectScopedMetric(
          instant,
          subject,
          rev,
          Set(action),
          ProjectRef(org, proj),
          org,
          iri"http://localhost/v1/projects/myorg/myproj",
          Set(nxv.Project),
          JsonObject.empty
        )
      }
    }
  }

  private val state = ProjectState(
    proj,
    projUuid,
    org,
    orgUuid,
    rev = rev,
    deprecated = false,
    markedForDeletion = false,
    description = Some(description),
    apiMappings = apiMappings,
    base = ProjectBase.unsafe(base.value),
    vocab = vocab.value,
    enforceSchema = true,
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("projects/project-state.json")

  test(s"Correctly serialize a ProjectState") {
    assertOutput(ProjectState.serializer, state, jsonState)
  }

  test(s"Correctly deserialize a ProjectState") {
    assertEquals(ProjectState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
