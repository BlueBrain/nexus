package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.{BlazegraphViewCreated, BlazegraphViewDeprecated, BlazegraphViewTagAdded, BlazegraphViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.{IndexingBlazegraphView => BlazegraphType}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.Json

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.VectorMap

class BlazegraphViewsSerializationSuite extends SerializationSuite {

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val indexingId       = nxv + "indexing-view"
  private val aggregateId      = nxv + "aggregate-view"
  private val indexingValue    = IndexingBlazegraphViewValue(
    Set(nxv + "some-schema"),
    Set(nxv + "SomeType"),
    Some(UserTag.unsafe("some.tag")),
    includeMetadata = false,
    includeDeprecated = false,
    Permission.unsafe("my/permission")
  )
  private val viewRef          = ViewRef(projectRef, indexingId)
  private val aggregateValue   = AggregateBlazegraphViewValue(NonEmptySet.of(viewRef))

  private val indexingSource  = indexingValue.toJson(indexingId)
  private val aggregateSource = aggregateValue.toJson(aggregateId)

  private val blazegraphViewsMapping = VectorMap(
    BlazegraphViewCreated(indexingId, projectRef, uuid, indexingValue, indexingSource, 1, instant, subject)       ->
      loadEvents("blazegraph", "indexing-view-created.json"),
    BlazegraphViewCreated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 1, instant, subject)    ->
      loadEvents("blazegraph", "aggregate-view-created.json"),
    BlazegraphViewUpdated(indexingId, projectRef, uuid, indexingValue, indexingSource, 2, instant, subject)       ->
      loadEvents("blazegraph", "indexing-view-updated.json"),
    BlazegraphViewUpdated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 2, instant, subject)    ->
      loadEvents("blazegraph", "aggregate-view-updated.json"),
    BlazegraphViewTagAdded(indexingId, projectRef, BlazegraphType, uuid, targetRev = 1, tag, 3, instant, subject) ->
      loadEvents("blazegraph", "view-tag-added.json"),
    BlazegraphViewDeprecated(indexingId, projectRef, BlazegraphType, uuid, 4, instant, subject)                   ->
      loadEvents("blazegraph", "view-deprecated.json")
  )

  private val sseEncoder = BlazegraphViewEvent.sseEncoder

  blazegraphViewsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(BlazegraphViewEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(BlazegraphViewEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val statesMapping = Map(
    (indexingId, indexingValue)   -> jsonContentOf("/blazegraph/database/indexing-view-state.json"),
    (aggregateId, aggregateValue) -> jsonContentOf("/blazegraph/database/aggregate-view-state.json")
  ).map { case ((id, value), json) =>
    BlazegraphViewState(
      id,
      projectRef,
      uuid,
      value,
      Json.obj("elastic" -> Json.fromString("value")),
      Tags(tag           -> 3),
      rev = 1,
      deprecated = false,
      createdAt = instant,
      createdBy = subject,
      updatedAt = instant,
      updatedBy = subject
    ) -> json
  }

  statesMapping.foreach { case (state, json) =>
    test(s"Correctly serialize state ${state.value.tpe}") {
      BlazegraphViewState.serializer.codec(state).equalsIgnoreArrayOrder(json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(BlazegraphViewState.serializer.codec.decodeJson(json), Right(state))
    }
  }

}
