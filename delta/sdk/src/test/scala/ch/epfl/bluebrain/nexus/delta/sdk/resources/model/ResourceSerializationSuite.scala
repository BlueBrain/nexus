package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourceInstanceFixture
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, Tags}
import io.circe.JsonObject

import java.time.Instant

class ResourceSerializationSuite extends SerializationSuite with ResourceInstanceFixture {

  private val sseEncoder = ResourceEvent.sseEncoder

  val instant: Instant = Instant.EPOCH
  val realm: Label     = Label.unsafe("myrealm")
  val subject: Subject = User("username", realm)
  val tag: UserTag     = UserTag.unsafe("mytag")
  private val jsonld   = JsonLdAssembly(myId, source, compacted, expanded, graph, remoteContexts)

  // format: off
  private val schemaRev = Revision(schemas.resources, 1)
  private val created        = ResourceCreated(projectRef, schemaRev, projectRef, jsonld, instant, subject, None)
  private val createdWithTag = created.copy(tag = Some(tag))
  private val updated        = ResourceUpdated(projectRef, schemaRev, projectRef, jsonld, 2, instant, subject, Some(tag))
  private val refreshed      = ResourceRefreshed(projectRef, schemaRev, projectRef, jsonld, 2, instant, subject)
  private val tagged         = ResourceTagAdded(myId, projectRef, types, 1, tag, 3, instant, subject)
  private val deprecated     = ResourceDeprecated(myId, projectRef, types, 4, instant, subject)
  private val undeprecated   = ResourceUndeprecated(myId, projectRef, types, 5, instant, subject)
  private val tagDeleted     = ResourceTagDeleted(myId, projectRef, types, tag, 5, instant, subject)
  private val schemaUpdated  = ResourceSchemaUpdated(myId, projectRef, schemaRev, projectRef, types, 6, instant, subject, Some(tag))
  // format: on

  private val resourcesMapping = List(
    (created, loadEvents("resources", "resource-created.json"), Set(Created)),
    (createdWithTag, loadEvents("resources", "resource-created-tagged.json"), Set(Created, Tagged)),
    (updated, loadEvents("resources", "resource-updated.json"), Set(Updated, Tagged)),
    (refreshed, loadEvents("resources", "resource-refreshed.json"), Set(Refreshed)),
    (tagged, loadEvents("resources", "resource-tagged.json"), Set(Tagged)),
    (deprecated, loadEvents("resources", "resource-deprecated.json"), Set(Deprecated)),
    (undeprecated, loadEvents("resources", "resource-undeprecated.json"), Set(Undeprecated)),
    (tagDeleted, loadEvents("resources", "resource-tag-deleted.json"), Set(TagDeleted)),
    (schemaUpdated, loadEvents("resources", "resource-schema-updated.json"), Set(Updated, Tagged))
  )

  resourcesMapping.foreach { case (event, (database, sse), action) =>
    test(s"Correctly serialize ${event.getClass.getSimpleName}") {
      assertOutput(ResourceEvent.serializer, event, database)
    }

    test(s"Correctly deserialize ${event.getClass.getSimpleName}") {
      assertEquals(ResourceEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getSimpleName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(ProjectRef(org, proj)), sse))
    }

    test(s"Correctly encode ${event.getClass.getSimpleName} to metric") {
      ResourceEvent.resourceEventMetricEncoder.toMetric.decodeJson(database).assertRight {
        ProjectScopedMetric(
          instant,
          subject,
          event.rev,
          action,
          ProjectRef(org, proj),
          event.id,
          event.types,
          JsonObject.empty
        )
      }
    }
  }

  private val resourcesMappingNoRemoteContexts = List(
    (created.noRemoteContext, jsonContentOf("resources/database/resource-created-no-remote-contexts.json")),
    (updated.noRemoteContext, jsonContentOf("resources/database/resource-updated-no-remote-contexts.json")),
    (refreshed.noRemoteContext, jsonContentOf("resources/database/resource-refreshed-no-remote-contexts.json"))
  )

  // TODO: Remove test after 1.10 migration.
  resourcesMappingNoRemoteContexts.foreach { case (event, database) =>
    test(s"Correctly deserialize a ${event.getClass.getSimpleName} with no RemoteContext") {
      assertEquals(ResourceEvent.serializer.codec.decodeJson(database), Right(event))
    }
  }

  private val state = ResourceState(
    myId,
    projectRef,
    projectRef,
    source,
    compacted,
    expanded,
    remoteContexts,
    rev = 2,
    deprecated = false,
    Revision(schemas.resources, 1),
    types,
    Tags(UserTag.unsafe("mytag") -> 3),
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState                = jsonContentOf("resources/resource-state.json")
  private val jsonStateNoRemoteContext = jsonContentOf("resources/resource-state-no-remote-contexts.json")

  test(s"Correctly serialize a ResourceState") {
    assertOutput(ResourceState.serializer, state, jsonState)
  }

  test(s"Correctly deserialize a ResourceState") {
    assertEquals(ResourceState.serializer.codec.decodeJson(jsonState), Right(state))
  }

  // TODO: Remove test after 1.10 migration.
  test("Correctly deserialize a ResourceState with no remote contexts") {
    assertEquals(
      ResourceState.serializer.codec.decodeJson(jsonStateNoRemoteContext),
      Right(state.copy(remoteContexts = Set.empty))
    )
  }

  implicit class ResourceEventTestOps(event: ResourceEvent) {
    def noRemoteContext: ResourceEvent = event match {
      case r: ResourceCreated   => r.copy(remoteContexts = Set.empty)
      case r: ResourceUpdated   => r.copy(remoteContexts = Set.empty)
      case r: ResourceRefreshed => r.copy(remoteContexts = Set.empty)
      case r                    => r
    }
  }

}
