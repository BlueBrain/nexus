package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, RawJsonLdContext}
import io.circe.JsonObject
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CompactedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "A compacted Json-LD" should {
    val expanded          = jsonContentOf("expanded.json")
    val context           = jsonContentOf("context.json")
    val expectedCompacted = jsonContentOf("compacted.json")

    "be constructed successfully" in {
      forAll(List(ContextFields.Skip, ContextFields.Include)) { f =>
        val compacted = JsonLd.compact(expanded, context, iri, f).accepted
        compacted.json.removeKeys(keywords.context) shouldEqual expectedCompacted.removeKeys(keywords.context)
        compacted.ctx.value shouldEqual context.topContextValueOrEmpty
        compacted.rootId shouldEqual iri
      }
    }

    "be constructed successfully with remote contexts" in {
      val context = jsonContentOf("/jsonld/compacted/context-with-remotes.json")
      forAll(List(ContextFields.Skip, ContextFields.Include)) { f =>
        val compacted = JsonLd.compact(expanded, context, iri, f).accepted
        compacted.json.removeKeys(keywords.context) shouldEqual expectedCompacted.removeKeys(keywords.context)
        compacted.ctx.value shouldEqual context.topContextValueOrEmpty
        compacted.rootId shouldEqual iri
      }
    }

    "fail to find the root IRI from a multi-root json" in {
      val input = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")
      JsonLd.compact(input, context, iri, ContextFields.Skip).rejectedWith[UnexpectedJsonLd]
    }

    "be constructed successfully from a multi-root json when using framing" in {
      val input     = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")
      val compacted = JsonLd.frame(input, context, iri, ContextFields.Skip).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual json"""{"id": "john-doé", "@type": "Person"}"""
    }

    "add literals" in {
      val compacted =
        CompactedJsonLd(JsonObject("@id" -> iri.asJson), RawJsonLdContext(context), iri, ContextFields.Skip)
      val result    = compacted.add("tags", "first").add("tags", 2).add("tags", 30L).add("tags", false)
      result.json.removeKeys(keywords.context) shouldEqual json"""{"@id": "$iri", "tags": [ "first", 2, 30, false ]}"""
    }

    "get @context fields" in {
      val compacted = JsonLd.compact(expanded, context, iri, ContextFields.Include).accepted
      compacted.base.value shouldEqual base.value
      compacted.vocab.value shouldEqual vocab.value
      compacted.aliases shouldEqual
        Map(
          "Person"     -> schema.Person,
          "Person2"    -> schema.Person,
          "deprecated" -> (schema + "deprecated"),
          "customid"   -> (vocab + "customid")
        )
      compacted.prefixMappings shouldEqual Map("schema" -> schema.base, "xsd" -> xsd.base, "xsd2" -> xsd.base)
    }

    "return self when attempted to convert again to compacted form with same values" in {
      val compacted = JsonLd.compact(expanded, context, iri, ContextFields.Include).accepted
      compacted.toCompacted(context, ContextFields.Include).accepted should be theSameInstanceAs compacted
    }

    "recompute context when attempted to convert again to compacted form with different ContextFields" in {
      val compacted = JsonLd.compact(expanded, context, iri, ContextFields.Include).accepted
      val result    = compacted.toCompacted(context, ContextFields.Skip).accepted
      result.obj should be theSameInstanceAs compacted.obj
      result.rootId should be theSameInstanceAs compacted.rootId
      result.ctxFields shouldEqual ContextFields.Skip
      result.ctx.value should be theSameInstanceAs compacted.ctx.value
    }

    "recompute compacted form when attempted to convert again to compacted form with different @context" in {
      val compacted = JsonLd.compact(expanded, context, iri, ContextFields.Include).accepted
      val context2  = context deepMerge json"""{"@context": {"other-something": {"@type": "@id"}}}"""
      val result    = compacted.toCompacted(context2, ContextFields.Skip).accepted
      result.json.removeKeys(keywords.context) shouldEqual compacted.json.removeKeys(keywords.context)
    }

    "be converted to expanded form" in {
      val compacted = JsonLd.compact(expanded, context, iri, ContextFields.Skip).accepted
      compacted.toExpanded.accepted shouldEqual JsonLd.expandedUnsafe(expanded, iri)
    }

    "be converted to graph" in {
      val compacted = JsonLd.compact(expanded, context, iri, ContextFields.Skip).accepted
      val graph     = compacted.toGraph.accepted
      val expected  = contentOf("ntriples.nt", "{bnode}" -> s"_:B${bNode(graph)}")
      graph.root shouldEqual iri
      graph.toNTriples.accepted.toString should equalLinesUnordered(expected)
    }
  }
}
