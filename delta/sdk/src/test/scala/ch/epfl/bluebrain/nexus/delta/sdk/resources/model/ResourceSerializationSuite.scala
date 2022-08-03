package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

import java.time.Instant

class ResourceSerializationSuite extends SerializationSuite {

  private val sseEncoder = ResourceEvent.sseEncoder

  val instant: Instant       = Instant.EPOCH
  val realm: Label           = Label.unsafe("myrealm")
  val subject: Subject       = User("username", realm)
  val org: Label             = Label.unsafe("myorg")
  val proj: Label            = Label.unsafe("myproj")
  val projectRef: ProjectRef = ProjectRef(org, proj)
  val myId: IriOrBNode.Iri   = nxv + "myId"

  val resource: Resource       =
    ResourceGen.resource(myId, projectRef, jsonContentOf("resources/resource.json", "id" -> myId))

  private val resourcesMapping = Map(
    ResourceCreated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      Set(schema.Person),
      resource.source,
      resource.compacted,
      resource.expanded,
      1,
      instant,
      subject
    ) -> loadEvents("resources", "resource-created.json"),
    ResourceUpdated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      Set(schema.Person),
      resource.source,
      resource.compacted,
      resource.expanded,
      2,
      instant,
      subject
    ) -> loadEvents("resources", "resource-updated.json"),
    ResourceTagAdded(
      myId,
      projectRef,
      Set(schema.Person),
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    ) -> loadEvents("resources", "resource-tagged.json"),
    ResourceDeprecated(
      myId,
      projectRef,
      Set(schema.Person),
      4,
      instant,
      subject
    ) -> loadEvents("resources", "resource-deprecated.json"),
    ResourceTagDeleted(
      myId,
      projectRef,
      Set(schema.Person),
      UserTag.unsafe("mytag"),
      5,
      instant,
      subject
    ) -> loadEvents("resources", "resource-tag-deleted.json")
  )

  resourcesMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(ResourceEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(ResourceEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(ProjectRef(org, proj)), sse))
    }
  }

  private val state = ResourceState(
    myId,
    projectRef,
    projectRef,
    resource.source,
    resource.compacted,
    resource.expanded,
    rev = 2,
    deprecated = false,
    Revision(schemas.resources, 1),
    Set(schema.Person),
    Tags(UserTag.unsafe("mytag") -> 3),
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("/resources/resource-state.json")

  test(s"Correctly serialize a ResourceState") {
    assertEquals(ResourceState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize a ResourceState") {
    assertEquals(ResourceState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
