package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourceInstanceFixture
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.JsonObject

import java.time.Instant

class ResourceSerializationSuite extends SerializationSuite with ResourceInstanceFixture {

  private val sseEncoder = ResourceEvent.sseEncoder

  val instant: Instant = Instant.EPOCH
  val realm: Label     = Label.unsafe("myrealm")
  val subject: Subject = User("username", realm)

  private val created    =
    ResourceCreated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      types,
      source,
      compacted,
      expanded,
      remoteContextRefs,
      1,
      instant,
      subject
    )
  private val updated    =
    ResourceUpdated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      types,
      source,
      compacted,
      expanded,
      remoteContextRefs,
      2,
      instant,
      subject
    )
  private val refreshed  = ResourceRefreshed(
    myId,
    projectRef,
    Revision(schemas.resources, 1),
    projectRef,
    types,
    compacted,
    expanded,
    remoteContextRefs,
    2,
    instant,
    subject
  )
  private val tagged     =
    ResourceTagAdded(
      myId,
      projectRef,
      types,
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    )
  private val deprecated =
    ResourceDeprecated(
      myId,
      projectRef,
      types,
      4,
      instant,
      subject
    )
  private val tagDeleted =
    ResourceTagDeleted(
      myId,
      projectRef,
      types,
      UserTag.unsafe("mytag"),
      5,
      instant,
      subject
    )

  private val resourcesMapping = List(
    (created, loadEvents("resources", "resource-created.json"), Created),
    (updated, loadEvents("resources", "resource-updated.json"), Updated),
    (refreshed, loadEvents("resources", "resource-refreshed.json"), Refreshed),
    (tagged, loadEvents("resources", "resource-tagged.json"), Tagged),
    (deprecated, loadEvents("resources", "resource-deprecated.json"), Deprecated),
    (tagDeleted, loadEvents("resources", "resource-tag-deleted.json"), TagDeleted)
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
          org,
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

  resourcesMappingNoRemoteContexts.foreach { case (event, database) =>
    test(s"Correctly deserialize missing remote contexts in ${event.getClass.getSimpleName}") {
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
    remoteContextRefs,
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

  private val jsonState = jsonContentOf("/resources/resource-state.json")

  test(s"Correctly serialize a ResourceState") {
    assertOutput(ResourceState.serializer, state, jsonState)
  }

  test(s"Correctly deserialize a ResourceState") {
    assertEquals(ResourceState.serializer.codec.decodeJson(jsonState), Right(state))
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
