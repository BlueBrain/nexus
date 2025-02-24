package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

class CompositeViewsSerializationSuite extends SerializationSuite with CompositeViewsFixture {

  private val viewSource = jsonContentOf("composite-view-source.json").removeAllKeys("token")
  private val viewId     = iri"http://example.com/composite-view"
  private val tag        = UserTag.unsafe("mytag")

  private val eventsMapping = loadEvents(
    "composite-views",
    // format: off
    CompositeViewCreated(viewId, project.ref, uuid, viewValue, viewSource, 1, epoch, subject)      -> "view-created.json",
    CompositeViewCreated(viewId, project.ref, uuid, viewValueNamed, viewSource, 1, epoch, subject) -> "named-view-created.json",
    CompositeViewUpdated(viewId, project.ref, uuid, viewValue, viewSource, 2, epoch, subject)      -> "view-updated.json",
    CompositeViewUpdated(viewId, project.ref, uuid, viewValueNamed, viewSource, 2, epoch, subject) -> "named-view-updated.json",
    CompositeViewTagAdded(viewId, projectRef, uuid, targetRev = 1, tag, 3, epoch, subject)         -> "view-tag-added.json",
    CompositeViewDeprecated(viewId, projectRef, uuid, 4, epoch, subject)                           -> "view-deprecated.json",
    CompositeViewUndeprecated(viewId, projectRef, uuid, 5, epoch, subject)                         -> "view-undeprecated.json"
    // format: on
  )

  private val eventSerializer = CompositeViewEvent.serializer
  private val sseEncoder      = CompositeViewEvent.sseEncoder

  eventsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getSimpleName}") {
      eventSerializer.codec(event).equalsIgnoreArrayOrder(database)
    }

    test(s"Correctly deserialize ${event.getClass.getSimpleName}") {
      assertEquals(eventSerializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getSimpleName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val state = CompositeViewState(
    viewId,
    projectRef,
    uuid,
    viewValue,
    viewSource,
    Tags(UserTag.unsafe("mytag") -> 3),
    rev = 1,
    deprecated = false,
    createdAt = epoch,
    createdBy = subject,
    updatedAt = epoch,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("composite-views/database/view-state.json")

  private val stateSerializer = CompositeViewState.serializer

  test(s"Correctly serialize a CompositeViewState") {
    stateSerializer.codec(state).equalsIgnoreArrayOrder(jsonState)
  }

  test(s"Correctly deserialize a CompositeViewState") {
    assertEquals(stateSerializer.codec.decodeJson(jsonState), Right(state))
  }

}
