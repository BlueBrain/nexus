package ch.epfl.bluebrain.nexus.rdf.derivation.encoder

import java.util.UUID

import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.View.{AggregateElasticSearchView, ElasticSearchView, ViewRef}
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.{mapping, View}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.{GraphEncoder, RdfSpec}

class MagnoliaGraphEncoderSpec extends RdfSpec {

  "A MagnoliaEncoder" should {
    "derive an Encoder for fixed ElasticSearchView" in {
      val view = ElasticSearchView(
        id = url"http://example.com/id",
        uuid = Some(UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01")),
        notAMapping = mapping,
        resourceSchemas = Set(nxv"Schema", nxv"Resource"),
        resourceTypes = Set(nxv"MyType", nxv"MyType2"),
        resourceTag = Some("one"),
        sourceAsText = Some(false)
      )
      val graph    = GraphEncoder[View].apply(view)
      val model    = toJenaModel(graph)
      val expected = toJenaModel(jsonWithContext("/elasticsearch-view.json"))

      model.isIsomorphicWith(expected) shouldEqual true
    }
    "derive an Encoder for fixed AggregateElasticSearchView" in {
      val view = AggregateElasticSearchView(
        id = url"http://example.com/id",
        uuid = Some(UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01")),
        views = List(
          ViewRef("account1/project1", url"http://example.com/view1"),
          ViewRef("account1/project2", url"http://example.com/view2"),
          ViewRef("account1/project3", url"http://example.com/view3"),
          ViewRef("account1/project4", url"http://example.com/view4")
        )
      )
      val graph    = GraphEncoder[View].apply(view)
      val model    = toJenaModel(graph)
      val expected = toJenaModel(jsonWithContext("/aggregate-elasticsearch-view.json"))

      model.isIsomorphicWith(expected) shouldEqual true
    }
  }
}
