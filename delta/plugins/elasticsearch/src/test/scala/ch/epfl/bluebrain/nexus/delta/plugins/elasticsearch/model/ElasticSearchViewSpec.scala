package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterBySchema, FilterByType, FilterDeprecated, SourceAsText}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterBySchema.FilterBySchemaConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, CirceLiteral, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class ElasticSearchViewSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with TestHelpers
    with IOValues
    with CirceEq
    with Fixtures {

  private val id      = nxv + "myview"
  private val project = ProjectRef.unsafe("org", "project")
  private val tagsMap = Tags(UserTag.unsafe("tag") -> 1)
  private val source  = json"""{"source": "value"}"""
  private val perm    = Permission.unsafe("views/query")

  "An IndexingElasticSearchView" should {
    val uuid                                                      = UUID.fromString("f85d862a-9ec0-4b9a-8aed-2938d7ca9981")
    def indexingView(pipeline: List[PipeStep]): ElasticSearchView = IndexingElasticSearchView(
      id,
      project,
      uuid,
      Some(UserTag.unsafe("mytag")),
      pipeline,
      jobj"""{"properties": {"@type": {"type": "keyword"}, "@id": {"type": "keyword"} } }""",
      jobj"""{"analysis": {"analyzer": {"nexus": {} } } }""",
      context = Some(ContextObject(jobj"""{"@vocab": "http://schema.org/"}""")),
      perm,
      tagsMap,
      source
    )
    "be converted to compacted Json-LD" in {
      forAll(
        List(
          PipeStep(FilterBySchema.label, FilterBySchemaConfig(Set(nxv.Schema)).toJsonLd),
          PipeStep(FilterByType.label, FilterByTypeConfig(Set(nxv + "Morphology")).toJsonLd),
          PipeStep.noConfig(SourceAsText.label).description("Formatting source as text")
        )        -> "jsonld/indexing-view-compacted-1.json" ::
          List(
            PipeStep.noConfig(FilterDeprecated.label),
            PipeStep.noConfig(DiscardMetadata.label)
          )      -> "jsonld/indexing-view-compacted-2.json" ::
          List() -> "jsonld/indexing-view-compacted-3.json" :: Nil
      ) { case (pipeline, expected) =>
        indexingView(pipeline).toCompactedJsonLd.accepted.json shouldEqual jsonContentOf(expected)
      }
    }
    "be converted to expanded Json-LD" in {
      indexingView(
        List(
          PipeStep(FilterBySchema.label, FilterBySchemaConfig(Set(nxv.Schema)).toJsonLd),
          PipeStep(FilterByType.label, FilterByTypeConfig(Set(nxv + "Morphology")).toJsonLd),
          PipeStep.noConfig(SourceAsText.label).description("Formatting source as text")
        )
      ).toExpandedJsonLd.accepted.json shouldEqual jsonContentOf("jsonld/indexing-view-expanded.json")
    }
  }
  "An AggregateElasticSearchView" should {
    val view: ElasticSearchView = AggregateElasticSearchView(
      id,
      project,
      NonEmptySet.of(ViewRef(project, nxv + "view1"), ViewRef(project, nxv + "view2")),
      tagsMap,
      source
    )
    "be converted to compacted Json-LD" in {
      view.toCompactedJsonLd.accepted.json should equalIgnoreArrayOrder(jsonContentOf("jsonld/agg-view-compacted.json"))
    }
    "be converted to expanded Json-LD" in {
      view.toExpandedJsonLd.accepted.json should equalIgnoreArrayOrder(jsonContentOf("jsonld/agg-view-expanded.json"))
    }
  }

}
