package ch.epfl.bluebrain.nexus.rdf.derivation.encoder

import java.util.UUID

import ch.epfl.bluebrain.nexus.rdf.{JenaSpec, RdfSpec}
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.View.{AggregateElasticSearchView, ElasticSearchView, ViewRef}
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.{mapping, View}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.derivation.DerivationError._
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.encoder.semiauto._
import ch.epfl.bluebrain.nexus.rdf.graph.GraphEncoder
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import com.github.ghik.silencer.silent

class MagnoliaGraphEncoderSpec extends RdfSpec with JenaSpec {

  "A MagnoliaEncoder" should {
    "derive an Encoder for fixed ElasticSearchView" in {
      val view = ElasticSearchView(
        id = url"http://example.com/id",
        uuid = Some(UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01")),
        notAMapping = mapping,
        resourceSchemas = Set(nxv / "Schema", nxv / "Resource"),
        resourceTypes = Set(nxv / "MyType", nxv / "MyType2"),
        resourceTag = Some("one"),
        sourceAsText = Some(false)
      )
      val graph    = GraphEncoder[View].apply(view)
      val model    = toJenaModel(graph)
      val expected = toJenaModel(jsonWithViewContext("/derivation/elasticsearch-view.json"))

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
      val expected = toJenaModel(jsonWithViewContext("/derivation/aggregate-elasticsearch-view.json"))

      model.isIsomorphicWith(expected) shouldEqual true
    }
    "throw DerivationError when colliding keys" in {
      case class TupleCase(one: Int, two: String)

      @silent implicit val config: Configuration = Configuration.default.copy(transformMemberNames = _ => "fix")
      intercept[DuplicatedParameters.type] {
        deriveConfiguredGraphEncoder[TupleCase]
      }
    }

    "throw DerivationError when case class param form an illegal Uri" in {
      case class TupleCase(one: Int, `>some`: String)

      @silent implicit val config: Configuration = Configuration.default
      val thrown = intercept[InvalidUriTransformation] {
        deriveConfiguredGraphEncoder[TupleCase]
      }
      thrown.getMessage shouldEqual "The parameter '>some' was converted to the invalid Uri 'https://bluebrain.github.io/nexus/vocabulary/>some'"
    }
  }
}
