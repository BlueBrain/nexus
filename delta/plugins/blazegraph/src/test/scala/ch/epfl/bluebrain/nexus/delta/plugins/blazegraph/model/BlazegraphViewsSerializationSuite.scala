package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.IndexingBlazegraphView as BlazegraphType
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label, ProjectRef}
import io.circe.Json

import java.time.Instant
import java.util.UUID

class BlazegraphViewsSerializationSuite extends SerializationSuite {

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val indexingId       = nxv + "indexing-view"
  private val aggregateId      = nxv + "aggregate-view"
  private val indexingValue    = IndexingBlazegraphViewValue(
    Some("viewName"),
    Some("viewDescription"),
    IriFilter.restrictedTo(nxv + "some-schema"),
    IriFilter.restrictedTo(nxv + "SomeType"),
    Some(UserTag.unsafe("some.tag")),
    includeMetadata = false,
    includeDeprecated = false,
    Permission.unsafe("my/permission")
  )
  private val viewRef          = ViewRef(projectRef, indexingId)
  private val aggregateValue   =
    AggregateBlazegraphViewValue(Some("viewName"), Some("viewDescription"), NonEmptySet.of(viewRef))

  private val indexingSource  = indexingValue.toJson(indexingId)
  private val aggregateSource = aggregateValue.toJson(aggregateId)

  private val defaultIndexingValue  = IndexingBlazegraphViewValue()
  private val defaultIndexingSource = defaultIndexingValue.toJson(indexingId)
  
  // format: off
  private val created      = BlazegraphViewCreated(indexingId, projectRef, uuid, defaultIndexingValue, defaultIndexingSource, 1, instant, subject)
  private val created1     = BlazegraphViewCreated(indexingId, projectRef, uuid, indexingValue, indexingSource, 1, instant, subject)
  private val created2     = BlazegraphViewCreated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 1, instant, subject)
  private val updated      = BlazegraphViewUpdated(indexingId, projectRef, uuid, defaultIndexingValue, defaultIndexingSource, 2, instant, subject)
  private val updated1     = BlazegraphViewUpdated(indexingId, projectRef, uuid, indexingValue, indexingSource, 2, instant, subject)
  private val updated2     = BlazegraphViewUpdated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 2, instant, subject)
  private val tagged       = BlazegraphViewTagAdded(indexingId, projectRef, BlazegraphType, uuid, targetRev = 1, tag, 3, instant, subject)
  private val deprecated   = BlazegraphViewDeprecated(indexingId, projectRef, BlazegraphType, uuid, 4, instant, subject)
  private val undeprecated = BlazegraphViewUndeprecated(indexingId, projectRef, BlazegraphType, uuid, 5, instant, subject)
  // format: on

  private val blazegraphViewsMapping = List(
    (created, loadEvents("blazegraph", "default-indexing-view-created.json")),
    (created1, loadEvents("blazegraph", "indexing-view-created.json")),
    (created2, loadEvents("blazegraph", "aggregate-view-created.json")),
    (updated, loadEvents("blazegraph", "default-indexing-view-updated.json")),
    (updated1, loadEvents("blazegraph", "indexing-view-updated.json")),
    (updated2, loadEvents("blazegraph", "aggregate-view-updated.json")),
    (tagged, loadEvents("blazegraph", "view-tag-added.json")),
    (deprecated, loadEvents("blazegraph", "view-deprecated.json")),
    (undeprecated, loadEvents("blazegraph", "view-undeprecated.json"))
  )

  private val sseEncoder = BlazegraphViewEvent.sseEncoder

  blazegraphViewsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getSimpleName}") {
      assertOutput(BlazegraphViewEvent.serializer, event, database)
    }

    test(s"Correctly deserialize ${event.getClass.getSimpleName}") {
      assertEquals(BlazegraphViewEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getSimpleName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val statesMapping = Map(
    (indexingId, indexingValue)   -> jsonContentOf("blazegraph/database/indexing-view-state.json"),
    (aggregateId, aggregateValue) -> jsonContentOf("blazegraph/database/aggregate-view-state.json")
  ).map { case ((id, value), json) =>
    BlazegraphViewState(
      id,
      projectRef,
      uuid,
      value,
      Json.obj("elastic" -> Json.fromString("value")),
      rev = 1,
      indexingRev = 1,
      deprecated = false,
      createdAt = instant,
      createdBy = subject,
      updatedAt = instant,
      updatedBy = subject
    ) -> json
  }

  statesMapping.foreach { case (state, json) =>
    test(s"Correctly serialize state ${state.value.tpe}") {
      assertOutputIgnoreOrder(BlazegraphViewState.serializer, state, json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(BlazegraphViewState.serializer.codec.decodeJson(json), Right(state))
    }
  }

}
