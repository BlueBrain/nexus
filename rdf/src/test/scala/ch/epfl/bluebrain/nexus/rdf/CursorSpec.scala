package ch.epfl.bluebrain.nexus.rdf

import java.util.UUID

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.{rdf, schema}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

class CursorSpec extends RdfSpec {

  private val id     = url"http://example.com/id"
  private val idNode = in"http://example.com/id"
  private val model  = toJenaModel(jsonWithContext("/composite-view.json"))
  private val graph  = fromJenaModel(id, model)

  "A Cursor" should {
    "return the main node of the graph" in {
      graph.cursor.focus.value shouldEqual idNode
    }
    "return a failed cursor" in {
      graph.cursor.down(schema.name).succeeded shouldEqual false
      graph.cursor.down(schema.name).failed shouldEqual true
      graph.cursor.down(schema.name).focus shouldEqual None
      graph.cursor.down(schema.name).values shouldEqual None
      graph.cursor.down(schema.name).cursors shouldEqual None
    }
    "return to top" in {
      graph.cursor.top.focus.value shouldEqual idNode
      graph.cursor.down(schema.name).top.focus.value shouldEqual idNode
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").top.focus.value shouldEqual idNode
    }
    "return to parent" in {
      graph.cursor.down(schema.name).parent.focus.value shouldEqual idNode
    }
    "descend to a single node" in {
      graph.cursor.down(nxv"uuid").as[UUID].rightValue shouldEqual UUID.fromString(
        "247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"
      )
      graph.cursor.down(nxv"uuid").succeeded shouldEqual true
      graph.cursor.down(nxv"uuid").failed shouldEqual false
      graph.cursor.down(nxv"uuid").values shouldEqual None
      graph.cursor.down(nxv"uuid").cursors shouldEqual None
    }
    "descend to a multiple nodes" in {
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").as[Set[AbsoluteIri]].rightValue shouldEqual Set(
        nxv"Schema",
        nxv"Resource"
      )
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").succeeded shouldEqual true
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").failed shouldEqual false
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").focus shouldEqual None
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").values.value
      graph.cursor.down(nxv"sources").downSet(nxv"resourceSchemas").cursors.value
    }
    "fail to descend to single node" in {
      graph.cursor.down(nxv"sources").down(nxv"resourceSchemas").succeeded shouldEqual false
      graph.cursor.down(nxv"sources").down(nxv"resourceSchemas").failed shouldEqual true
      graph.cursor.down(nxv"sources").down(nxv"resourceSchemas").values shouldEqual None
      graph.cursor.down(nxv"sources").down(nxv"resourceSchemas").focus shouldEqual None
      graph.cursor.down(nxv"sources").down(nxv"resourceSchemas").cursors shouldEqual None
    }
    "fail to descend when selection is a literal" in {
      graph.cursor.down(nxv"uuid").down(rdf.tpe).succeeded shouldEqual false
      graph.cursor.down(nxv"uuid").downSet(rdf.tpe).succeeded shouldEqual false
    }
    "narrow cursor selection" in {
      graph.cursor.downSet(nxv"projections").down(nxv"sourceAsText").narrow.as[Boolean].rightValue shouldEqual true
    }
    "fail to narrow cursor selection" in {
      graph.cursor.downSet(nxv"projections").narrow.failed shouldEqual true
    }
    "ascend to single node" in {
      graph.cursor
        .downSet(nxv"projections")
        .down(nxv"sourceAsText")
        .narrow
        .up(nxv"sourceAsText")
        .as[AbsoluteIri]
        .rightValue shouldEqual url"http://example.com/projection1"
    }
    "fail to ascend to single node" in {
      graph.cursor
        .downSet(nxv"projections")
        .down(nxv"sourceAsText")
        .narrow
        .up(nxv"sourceAsText")
        .down(nxv"resourceTypes")
        .up(nxv"resourceTypes")
        .failed shouldEqual true
    }
    "ascend to multiple nodes" in {
      graph.cursor
        .downSet(nxv"projections")
        .down(nxv"sourceAsText")
        .narrow
        .up(nxv"sourceAsText")
        .down(nxv"resourceTypes")
        .upSet(nxv"resourceTypes")
        .up(nxv"projections")
        .narrow
        .as[AbsoluteIri]
        .rightValue shouldEqual id
    }
    "ascend to multiple nodes when selection is on multiple nodes" in {
      graph.cursor
        .downSet(nxv"sources")
        .downSet(nxv"resourceTypes")
        .upSet(nxv"resourceTypes")
        .down(nxv"sourceAsText")
        .narrow
        .as[Boolean]
        .rightValue shouldEqual true
    }
    "maintain failure state when descending or ascending" in {
      val failed = graph.cursor.down(nxv"unknownPredicate")
      failed.failed shouldEqual true
      failed.up(nxv"unknownPredicate").failed shouldEqual true
      failed.upSet(nxv"unknownPredicate").failed shouldEqual true
      failed.down(nxv"unknownPredicate").failed shouldEqual true
      failed.downSet(nxv"unknownPredicate").failed shouldEqual true
    }
  }
}
