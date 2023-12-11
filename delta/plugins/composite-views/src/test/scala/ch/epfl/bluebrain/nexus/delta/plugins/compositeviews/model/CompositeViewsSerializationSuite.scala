package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent.{CompositeViewCreated, CompositeViewDeprecated, CompositeViewTagAdded, CompositeViewUndeprecated, CompositeViewUpdated}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.JsonObject

import java.time.Instant

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
  private val metricEncoder   = CompositeViewEvent.compositeViewMetricEncoder

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

    test(s"Correctly encode ${event.getClass.getSimpleName} to metric") {
      metricEncoder.toMetric.decodeJson(database).assertRight {
        ProjectScopedMetric(
          Instant.EPOCH,
          subject,
          event.rev,
          event match {
            case _: CompositeViewCreated      => Created
            case _: CompositeViewUpdated      => Updated
            case _: CompositeViewTagAdded     => Tagged
            case _: CompositeViewDeprecated   => Deprecated
            case _: CompositeViewUndeprecated => Undeprecated
          },
          projectRef,
          Label.unsafe("myorg"),
          event.id,
          Set(nxv.View, compositeViewType),
          JsonObject.empty
        )
      }
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
