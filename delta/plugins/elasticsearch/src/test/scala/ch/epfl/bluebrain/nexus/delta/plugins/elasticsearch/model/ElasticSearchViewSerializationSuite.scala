package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.{ElasticSearchViewCreated, ElasticSearchViewDeprecated, ElasticSearchViewTagAdded, ElasticSearchViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{ElasticSearch => ElasticSearchType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{FilterBySchema, FilterByType, SourceAsText}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.Json

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.VectorMap

class ElasticSearchViewSerializationSuite extends SerializationSuite {

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val indexingId       = nxv + "indexing-view"
  private val aggregateId      = nxv + "aggregate-view"
  private val indexingValue    = IndexingElasticSearchViewValue(
    Some(UserTag.unsafe("some.tag")),
    List(
      FilterBySchema(Set(nxv + "some-schema")).description("Only keeping a specific schema"),
      FilterByType(Set(nxv + "SomeType")),
      SourceAsText()
    ),
    Some(jobj"""{"properties": {}}"""),
    Some(jobj"""{"analysis": {}}"""),
    context = Some(ContextObject(jobj"""{"@vocab": "http://schema.org/"}""")),
    Permission.unsafe("my/permission")
  )
  private val viewRef          = ViewRef(projectRef, indexingId)
  private val aggregateValue   = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef))

  private val indexingSource  = indexingValue.toJson(indexingId)
  private val aggregateSource = aggregateValue.toJson(aggregateId)

  // format: off
  private val elasticsearchViewsMapping = VectorMap(
    ElasticSearchViewCreated(indexingId, projectRef, uuid, indexingValue, indexingSource, 1, instant, subject)            ->
      loadEvents("elasticsearch","indexing-view-created.json"),
    ElasticSearchViewCreated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 1, instant, subject)         ->
      loadEvents("elasticsearch","aggregate-view-created.json"),
    ElasticSearchViewUpdated(indexingId, projectRef, uuid, indexingValue, indexingSource, 2, instant, subject)            ->
      loadEvents("elasticsearch","indexing-view-updated.json"),
    ElasticSearchViewUpdated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 2, instant, subject)         ->
      loadEvents("elasticsearch","aggregate-view-updated.json"),
    ElasticSearchViewTagAdded(indexingId, projectRef, ElasticSearchType, uuid, targetRev = 1, tag, 3, instant, subject)   ->
      loadEvents("elasticsearch","view-tag-added.json"),
    ElasticSearchViewDeprecated(indexingId, projectRef, ElasticSearchType, uuid, 4, instant, subject)                     ->
      loadEvents("elasticsearch","view-deprecated.json")
  )
  // format: on

  private val sseEncoder = ElasticSearchViewEvent.sseEncoder

  elasticsearchViewsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(ElasticSearchViewEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(ElasticSearchViewEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val statesMapping = Map(
    (indexingId, indexingValue)   -> jsonContentOf("/elasticsearch/database/indexing-view-state.json"),
    (aggregateId, aggregateValue) -> jsonContentOf("/elasticsearch/database/aggregate-view-state.json")
  ).map { case ((id, value), json) =>
    ElasticSearchViewState(
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
      ElasticSearchViewState.serializer.codec(state).equalsIgnoreArrayOrder(json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(ElasticSearchViewState.serializer.codec.decodeJson(json), Right(state))
    }
  }

}
