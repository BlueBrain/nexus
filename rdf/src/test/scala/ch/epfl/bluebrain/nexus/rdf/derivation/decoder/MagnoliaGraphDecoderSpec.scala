package ch.epfl.bluebrain.nexus.rdf.derivation.decoder

import java.util.UUID

import cats.data._
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.{nxv, rdf}
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.Values.{ListValue, SetValue, StringValue}
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.View.{AggregateElasticSearchView, ElasticSearchView, ViewRef}
import ch.epfl.bluebrain.nexus.rdf.derivation.Fixture.{mapping, Values, View}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf._

class MagnoliaGraphDecoderSpec extends RdfSpec {

  "A MagnoliaDecoder" should {
    "derive a Decoder for fixed ElasticSearchView" in {
      val model = toJenaModel(jsonWithContext("/elasticsearch-view.json"))
      val graph = fromJenaModel(url"http://example.com/id", model)

      val expected = ElasticSearchView(
        id = url"http://example.com/id",
        uuid = Some(UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01")),
        notAMapping = mapping,
        resourceSchemas = Set(nxv"Schema", nxv"Resource"),
        resourceTypes = Set(nxv"MyType", nxv"MyType2"),
        resourceTag = Some("one"),
        sourceAsText = Some(false)
      )

      GraphDecoder[View].apply(graph.cursor) shouldEqual Right(expected)
    }

    "fail to Decoder Value with non allowed empty fields" in {
      val id        = url"http://example.com/id"
      val emptyp    = nxv.base + "empty"
      val nonEmptyp = nxv.base + "nonEmpty"
      val errTypes  = Array("NonEmptyString", "NonEmptyList", "NonEmptySet")
      val list = List(
        Graph(id, Set((id, rdf.tpe, nxv.base + "StringValue"), (id, emptyp, ""), (id, nonEmptyp, ""))),
        Graph(id, Set((id, rdf.tpe, nxv.base + "ListValue"), (id, emptyp, rdf.nil), (id, nonEmptyp, rdf.nil))),
        Graph(id, Set((id, rdf.tpe, nxv.base + "SetValue")))
      )
      forAll(list.zipWithIndex) {
        case (graph, i) =>
          val err = GraphDecoder[Values].apply(graph.cursor).leftValue
          err.message shouldEqual s"Unable to decode node as a ${errTypes(i)}"
      }
    }

    "derive a Decoder for Value with valid nonempty fields" in {
      val id        = url"http://example.com/id"
      val emptyp    = nxv.base + "empty"
      val nonEmptyp = nxv.base + "nonEmpty"
      val bNode     = Node.blank
      // format: off
      val list = List(
        Graph(id, Set((id, rdf.tpe, nxv.base + "StringValue"), (id, emptyp, ""), (id, nonEmptyp, "Non empty")))                                                 -> StringValue(id, NonEmptyString("Non empty").value, ""),
        Graph(id, Set((id, rdf.tpe, nxv.base + "ListValue"), (id, emptyp, rdf.nil), (id, nonEmptyp, bNode), (bNode, rdf.first, 1), (bNode, rdf.rest, rdf.nil))) -> ListValue(id, NonEmptyList(1, List.empty), List.empty),
        Graph(id, Set((id, rdf.tpe, nxv.base + "SetValue"), (id, nonEmptyp, 1)))                                                                                -> SetValue(id, NonEmptySet.one[Int](1), Set.empty)
      )
      // format: on
      forAll(list) {
        case (graph, expected) =>
          GraphDecoder[Values].apply(graph.cursor).rightValue shouldEqual expected
      }
    }

    "derive a Decoder for fixed AggregateElasticSearchView" in {
      val model = toJenaModel(jsonWithContext("/aggregate-elasticsearch-view.json"))
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
  }
}
