package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.serialization

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.{ElasticSearchViewCreated, ElasticSearchViewDeprecated, ElasticSearchViewTagAdded, ElasticSearchViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{ElasticSearch => ElasticSearchType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{FilterBySchema, FilterByType, SourceAsText}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.VectorMap

class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with CirceLiteral
    with CancelAfterFailure {

  override val serializer = new EventSerializer(system.asInstanceOf[ExtendedActorSystem])

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = TagLabel.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val indexingId       = nxv + "indexing-view"
  private val aggregateId      = nxv + "aggregate-view"
  private val indexingValue    = IndexingElasticSearchViewValue(
    Some(TagLabel.unsafe("some.tag")),
    List(
      FilterBySchema.definition(Set(nxv + "some-schema")).copy(description = Some("Only keeping a specific schema")),
      FilterByType.definition(Set(nxv + "SomeType")),
      SourceAsText.definition
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
  private val elasticsearchViewsMapping: Map[ElasticSearchViewEvent, Json] = VectorMap(
    ElasticSearchViewCreated(indexingId, projectRef, uuid, indexingValue, indexingSource, 1, instant, subject)            -> jsonContentOf("/serialization/indexing-view-created.json"),
    ElasticSearchViewCreated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 1, instant, subject)         -> jsonContentOf("/serialization/aggregate-view-created.json"),
    ElasticSearchViewUpdated(indexingId, projectRef, uuid, indexingValue, indexingSource, 2, instant, subject)            -> jsonContentOf("/serialization/indexing-view-updated.json"),
    ElasticSearchViewUpdated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 2, instant, subject)         -> jsonContentOf("/serialization/aggregate-view-updated.json"),
    ElasticSearchViewTagAdded(indexingId, projectRef, ElasticSearchType, uuid, targetRev = 1, tag, 3, instant, subject)   -> jsonContentOf("/serialization/view-tag-added.json"),
    ElasticSearchViewDeprecated(indexingId, projectRef, ElasticSearchType, uuid, 4, instant, subject)                     -> jsonContentOf("/serialization/view-deprecated.json")
  )
  // format: on

  "An EventSerializer" should behave like eventToJsonSerializer("elasticsearch", elasticsearchViewsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("elasticsearch", elasticsearchViewsMapping)

}
