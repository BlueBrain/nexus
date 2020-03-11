package ch.epfl.bluebrain.nexus.rdf.derivation.decoder

import java.util.UUID

import cats.data._
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.{nxv, rdf}
import ch.epfl.bluebrain.nexus.rdf._
import ch.epfl.bluebrain.nexus.rdf.derivation.DerivationError._
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.Values._
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.View._
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture._
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.Configuration
import ch.epfl.bluebrain.nexus.rdf.derivation.configured.decoder.semiauto._
import ch.epfl.bluebrain.nexus.rdf.graph.{Graph, GraphDecoder}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import com.github.ghik.silencer.silent

class MagnoliaGraphDecoderSpec extends RdfSpec with JenaSpec {

  "A MagnoliaDecoder" should {
    "derive a Decoder for fixed ElasticSearchView" in {
      val model = toJenaModel(jsonWithViewContext("/derivation/elasticsearch-view.json"))
      val graph = fromJenaModel(url"http://example.com/id", model)

      val expected = ElasticSearchView(
        id = url"http://example.com/id",
        uuid = Some(UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01")),
        notAMapping = mapping,
        resourceSchemas = Set(nxv / "Schema", nxv / "Resource"),
        resourceTypes = Set(nxv / "MyType", nxv / "MyType2"),
        resourceTag = Some("one"),
        sourceAsText = Some(false)
      )

      GraphDecoder[View].apply(graph.cursor) shouldEqual Right(expected)
    }

    "fail to Decoder Value with non allowed empty fields" in {
      val id        = url"http://example.com/id"
      val emptyp    = nxv / "empty"
      val nonEmptyp = nxv / "nonEmpty"
      val errTypes  = Array("NonEmptyString", "NonEmptyList", "NonEmptySet")
      val list = List(
        Graph(id, Set((id, rdf.tpe, nxv / "StringValue"), (id, emptyp, ""), (id, nonEmptyp, ""))),
        Graph(id, Set((id, rdf.tpe, nxv / "ListValue"), (id, emptyp, rdf.nil), (id, nonEmptyp, rdf.nil))),
        Graph(id, Set((id, rdf.tpe, nxv / "SetValue")))
      )
      forAll(list.zipWithIndex) {
        case (graph, i) =>
          val err = GraphDecoder[Values].apply(graph.cursor).leftValue
          err.message shouldEqual s"Unable to decode node as a ${errTypes(i)}"
      }
    }

    "derive a Decoder for Value with valid nonempty fields" in {
      val id        = url"http://example.com/id"
      val emptyp    = nxv / "empty"
      val nonEmptyp = nxv / "nonEmpty"
      val bNode     = Node.blank
      // format: off
      val list = List(
        Graph(id, Set((id, rdf.tpe, nxv / "StringValue"), (id, emptyp, ""), (id, nonEmptyp, "Non empty")))                                                 -> StringValue(id, NonEmptyString("Non empty").value, ""),
        Graph(id, Set((id, rdf.tpe, nxv / "ListValue"), (id, emptyp, rdf.nil), (id, nonEmptyp, bNode), (bNode, rdf.first, 1), (bNode, rdf.rest, rdf.nil))) -> ListValue(id, NonEmptyList(1, List.empty), List.empty),
        Graph(id, Set((id, rdf.tpe, nxv / "SetValue"), (id, nonEmptyp, 1)))                                                                                -> SetValue(id, NonEmptySet.one[Int](1), Set.empty)
      )
      // format: on
      forAll(list) {
        case (graph, expected) =>
          GraphDecoder[Values].apply(graph.cursor).rightValue shouldEqual expected
      }
    }

    "derive a Decoder for fixed AggregateElasticSearchView" in {
      val model = toJenaModel(jsonWithViewContext("/derivation/aggregate-elasticsearch-view.json"))
      val graph = fromJenaModel(url"http://example.com/id", model)
      val expected = AggregateElasticSearchView(
        id = url"http://example.com/id",
        uuid = Some(UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01")),
        views = List(
          ViewRef("account1/project1", url"http://example.com/view1"),
          ViewRef("account1/project2", url"http://example.com/view2"),
          ViewRef("account1/project3", url"http://example.com/view3"),
          ViewRef("account1/project4", url"http://example.com/view4")
        )
      )

      GraphDecoder[View].apply(graph.cursor) shouldEqual Right(expected)
    }

    "throw DerivationError when colliding keys" in {
      case class TupleCase(one: Int, two: String)

      @silent implicit val config: Configuration = Configuration.default.copy(transformMemberNames = _ => "fix")
      intercept[DuplicatedParameters.type] {
        deriveConfiguredGraphDecoder[TupleCase]
      }
    }

    "throw DerivationError when case class param form an illegal Uri" in {
      case class TupleCase(one: Int, `>some`: String)

      @silent implicit val config: Configuration = Configuration.default
      val thrown = intercept[InvalidUriTransformation] {
        deriveConfiguredGraphDecoder[TupleCase]
      }
      thrown.getMessage shouldEqual "The parameter '>some' was converted to the invalid Uri 'https://bluebrain.github.io/nexus/vocabulary/>some'"
    }
  }
}
