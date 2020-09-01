package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
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
    val input    = jsonContentOf(s"/jsonld/compacted/input.json")
    val context  = jsonContentOf(s"/jsonld/compacted/context.json")
    val expected = jsonContentOf(s"/jsonld/compacted/output.json")

    "be constructed successfully" in {
      forAll(List(ContextFields.Skip, ContextFields.Include)) { f =>
        val compacted = JsonLd.compact(input, context, iri, f).runSyncUnsafe()
        compacted.json.removeKeys(keywords.context) shouldEqual expected.removeKeys(keywords.context)
        compacted.ctx.value shouldEqual context.topContextValueOrEmpty
        compacted.rootId shouldEqual iri
      }
    }

    "be constructed successfully with remote contexts" in {
      val context = jsonContentOf(s"/jsonld/compacted/context-with-remotes.json")
      forAll(List(ContextFields.Skip, ContextFields.Include)) { f =>
        val compacted = JsonLd.compact(input, context, iri, f).runSyncUnsafe()
        compacted.json.removeKeys(keywords.context) shouldEqual expected.removeKeys(keywords.context)
        compacted.ctx.value shouldEqual context.topContextValueOrEmpty
        compacted.rootId shouldEqual iri
      }
    }

    "be constructed successfully from a multi-root json" in {
      val input     = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")
      val compacted = JsonLd.compact(input, context, iri, ContextFields.Skip).runSyncUnsafe()
      compacted.json.removeKeys(keywords.context) shouldEqual json"""{"id": "john-doé", "@type": "Person"}"""
    }

    "add literals" in {
      val compacted = CompactedJsonLd(JsonObject("@id" -> iri.asJson), RawJsonLdContext(context), iri)
      val result    = compacted.add("tags", "first").add("tags", 2).add("tags", 30L).add("tags", false)
      result.json.removeKeys(keywords.context) shouldEqual json"""{"@id": "$iri", "tags": [ "first", 2, 30, false ]}"""
    }

    "get @context fields" in {
      val compacted = JsonLd.compact(input, context, iri, ContextFields.Include).runSyncUnsafe()
      compacted.base.value shouldEqual base.value
      compacted.vocab.value shouldEqual vocab.value
      compacted.aliases shouldEqual
        Map(
          "Person"     -> (schema + "Person"),
          "deprecated" -> (schema + "deprecated"),
          "customid"   -> (vocab + "customid")
        )
      compacted.prefixMappings shouldEqual Map("schema" -> schema.base, "xsd" -> xsd.base)
    }
  }
}
