package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.ElasticSearch as ElasticSearchType
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label, ProjectRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{FilterBySchema, FilterByType, SourceAsText}
import io.circe.Json

import java.time.Instant
import java.util.UUID

class ElasticSearchViewSerializationSuite extends SerializationSuite {

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = UserTag.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val indexingId       = nxv + "indexing-view"
  private val aggregateId      = nxv + "aggregate-view"
  private val indexingValue    = IndexingElasticSearchViewValue(
    Some("viewName"),
    Some("viewDescription"),
    Some(UserTag.unsafe("some.tag")),
    List(
      PipeStep(FilterBySchema(IriFilter.restrictedTo(nxv + "some-schema")))
        .description("Only keeping a specific schema"),
      PipeStep(FilterByType(IriFilter.restrictedTo(nxv + "SomeType"))),
      PipeStep.noConfig(SourceAsText.ref)
    ),
    Some(jobj"""{"properties": {}}"""),
    Some(jobj"""{"analysis": {}}"""),
    context = Some(ContextObject(jobj"""{"@vocab": "http://schema.org/"}""")),
    Permission.unsafe("my/permission")
  )
  private val viewRef          = ViewRef(projectRef, indexingId)
  private val aggregateValue   =
    AggregateElasticSearchViewValue(Some("viewName"), Some("viewDescription"), NonEmptySet.of(viewRef))

  private val indexingSource  = indexingValue.toJson(indexingId)
  private val aggregateSource = aggregateValue.toJson(aggregateId)

  private val defaultIndexingValue = IndexingElasticSearchViewValue(name = None, description = None)

  // format: off
  private val created1   = ElasticSearchViewCreated(indexingId, projectRef, uuid, indexingValue, indexingSource, 1, instant, subject)
  private val created2   = ElasticSearchViewCreated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 1, instant, subject)
  private val updated1   = ElasticSearchViewUpdated(indexingId, projectRef, uuid, indexingValue, indexingSource, 2, instant, subject)
  private val updated2   = ElasticSearchViewUpdated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 2, instant, subject)
  private val tagged     = ElasticSearchViewTagAdded(indexingId, projectRef, ElasticSearchType, uuid, targetRev = 1, tag, 3, instant, subject)
  private val deprecated = ElasticSearchViewDeprecated(indexingId, projectRef, ElasticSearchType, uuid, 4, instant, subject)
  private val undeprecated = ElasticSearchViewUndeprecated(indexingId, projectRef, ElasticSearchType, uuid, 5, instant, subject)
  // format: on

  private val elasticsearchViewsMapping = List(
    (created1, loadEvents("elasticsearch", "indexing-view-created.json")),
    (created2, loadEvents("elasticsearch", "aggregate-view-created.json")),
    (updated1, loadEvents("elasticsearch", "indexing-view-updated.json")),
    (updated2, loadEvents("elasticsearch", "aggregate-view-updated.json")),
    (tagged, loadEvents("elasticsearch", "view-tag-added.json")),
    (deprecated, loadEvents("elasticsearch", "view-deprecated.json")),
    (undeprecated, loadEvents("elasticsearch", "view-undeprecated.json"))
  )

  private val sseEncoder = ElasticSearchViewEvent.sseEncoder

  elasticsearchViewsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getSimpleName}") {
      assertOutput(ElasticSearchViewEvent.serializer, event, database)
    }

    test(s"Correctly deserialize ${event.getClass.getSimpleName}") {
      assertEquals(ElasticSearchViewEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getSimpleName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(projectRef), sse))
    }
  }

  private val statesMapping = Map(
    (indexingId, indexingValue)        -> jsonContentOf("elasticsearch/database/indexing-view-state.json"),
    (indexingId, defaultIndexingValue) -> jsonContentOf("elasticsearch/database/default-indexing-view-state.json"),
    (aggregateId, aggregateValue)      -> jsonContentOf("elasticsearch/database/aggregate-view-state.json")
  ).map { case ((id, value), json) =>
    ElasticSearchViewState(
      id,
      projectRef,
      uuid,
      value,
      Json.obj("elastic" -> Json.fromString("value")),
      Tags(tag           -> 3),
      rev = 1,
      indexingRev = IndexingRev.init,
      deprecated = false,
      createdAt = instant,
      createdBy = subject,
      updatedAt = instant,
      updatedBy = subject
    ) -> json
  }

  statesMapping.foreach { case (state, json) =>
    test(s"Correctly serialize state ${state.value.tpe}") {
      assertOutputIgnoreOrder(ElasticSearchViewState.serializer, state, json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(ElasticSearchViewState.serializer.codec.decodeJson(json), Right(state))
    }
  }

}
