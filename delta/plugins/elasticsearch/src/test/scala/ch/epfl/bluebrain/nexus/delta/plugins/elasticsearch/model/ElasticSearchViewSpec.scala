package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.data.NonEmptySet
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.collection.immutable.SortedSet

class ElasticSearchViewSpec extends AnyWordSpecLike with Matchers with CirceLiteral with TestHelpers with IOValues {
  private val id      = nxv + "myview"
  private val project = ProjectRef.unsafe("org", "project")
  private val tagsMap = Map(TagLabel.unsafe("tag") -> 1L)
  private val source  = json"""{"source": "value"}"""
  private val perm    = Permission.unsafe("views/query")

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.elasticsearch -> jsonContentOf("/contexts/elasticsearch.json")
    )

  "An IndexingElasticSearchView" should {
    val uuid                    = UUID.fromString("f85d862a-9ec0-4b9a-8aed-2938d7ca9981")
    val view: ElasticSearchView = IndexingElasticSearchView(
      id,
      project,
      uuid,
      Set(nxv.Schema),
      Set(nxv + "Morphology"),
      Some(TagLabel.unsafe("mytag")),
      sourceAsText = true,
      includeMetadata = true,
      includeDeprecated = true,
      json"""{"properties": {"@type": {"type": "keyword"}, "@id": {"type": "keyword"} } }""",
      Some(json"""{"analysis": {"analyzer": {"nexus": {} } } }"""),
      perm,
      tagsMap,
      source
    )
    "be converted to compacted Json-LD" in {
      view.toCompactedJsonLd.accepted.json shouldEqual jsonContentOf("jsonld/indexing-view-compacted.json")
    }
    "be converted to expanded Json-LD" in {
      view.toExpandedJsonLd.accepted.json shouldEqual jsonContentOf("jsonld/indexing-view-expanded.json")
    }
  }
  "An AggregateElasticSearchView" should {
    val view: ElasticSearchView = AggregateElasticSearchView(
      id,
      project,
      NonEmptySet.fromSetUnsafe(SortedSet(ViewRef(project, nxv + "view1"), ViewRef(project, nxv + "view2"))),
      tagsMap,
      source
    )
    "be converted to compacted Json-LD" in {
      view.toCompactedJsonLd.accepted.json shouldEqual jsonContentOf("jsonld/agg-view-compacted.json")
    }
    "be converted to expanded Json-LD" in {
      view.toExpandedJsonLd.accepted.json shouldEqual jsonContentOf("jsonld/agg-view-expanded.json")
    }
  }

}
