package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, CirceLiteral, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class ElasticSearchViewSpec
    extends AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with TestHelpers
    with IOValues
    with CirceEq
    with RemoteContextResolutionFixture {

  private val id      = nxv + "myview"
  private val project = ProjectRef.unsafe("org", "project")
  private val tagsMap = Map(TagLabel.unsafe("tag") -> 1L)
  private val source  = json"""{"source": "value"}"""
  private val perm    = Permission.unsafe("views/query")

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
