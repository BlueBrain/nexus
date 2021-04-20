package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.RemoteContextCircularDependency
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdOptions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ExpandedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures {

  implicit val opts: JsonLdOptions = JsonLdOptions(base = Some(iri"http://default.com/"))

  "An expanded Json-LD" should {
    val example          = "http://example.com"
    val compacted        = jsonContentOf("compacted.json")
    val context          = compacted.topContextValueOrEmpty
    val expectedExpanded = jsonContentOf("expanded.json")

    "be constructed successfully" in {
      ExpandedJsonLd(compacted).accepted shouldEqual ExpandedJsonLd.expanded(expectedExpanded).rightValue
    }

    "be constructed successfully with a base defined in JsonLdOptions" in {
      val compactedNoBase        = jsonContentOf("compacted-no-base.json")
      val expectedExpandedNoBase = jsonContentOf("expanded-no-base.json")
      ExpandedJsonLd(compactedNoBase).accepted shouldEqual ExpandedJsonLd.expanded(expectedExpandedNoBase).rightValue
    }

    "be constructed successfully without @id" in {
      val name             = vocab + "name"
      val expectedExpanded = json"""[{"@type": ["${schema.Person}"], "$name": [{"@value": "Me"} ] } ]"""
      val compacted        = json"""{"@type": "Person", "name": "Me"}""".addContext(context.contextObj)
      val expanded         = ExpandedJsonLd(compacted).accepted
      expanded.json shouldEqual expectedExpanded
      expanded.rootId shouldBe a[BNode]
    }

    "be constructed successfully with remote contexts" in {
      val compacted = jsonContentOf("/jsonld/expanded/input-with-remote-context.json")
      ExpandedJsonLd(compacted).accepted shouldEqual ExpandedJsonLd.expanded(expectedExpanded).rightValue
    }

    "be constructed successfully with injected @id" in {
      val compactedNoId = compacted.removeKeys("id")
      ExpandedJsonLd(compactedNoId).accepted.replaceId(iri) shouldEqual
        ExpandedJsonLd.expanded(expectedExpanded).rightValue
    }

    "be constructed empty" in {
      val compacted = json"""{"@id": "$iri"}"""
      val expanded  = ExpandedJsonLd(compacted).accepted
      expanded.json shouldEqual json"""[ {} ]"""
      expanded.rootId shouldEqual iri
    }

    "be constructed with multiple root objects" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"$example/batman"
      ExpandedJsonLd(multiRoot).accepted.json shouldEqual
        json"""[{"${keywords.graph}": [
              {"@id": "$iri", "@type": ["$example/Person"], "$example/name": [{"@value": "John"} ] },
               {"@id": "$batmanIri", "@type": ["$example/Person", "$example/Hero"], "$example/name": [{"@value": "Batman"} ] }
              ]}]"""
    }

    "replace @id" in {
      val newIri   = iri"$example/myid"
      val expanded = ExpandedJsonLd(compacted).accepted.replaceId(newIri)
      expanded.rootId shouldEqual newIri
      expanded.json shouldEqual expectedExpanded.replace(keywords.id -> iri, newIri)
    }

    "be converted to compacted form" in {
      val expanded = ExpandedJsonLd(compacted).accepted
      val result   = expanded.toCompacted(context).accepted
      result.json.removeKeys(keywords.context) shouldEqual compacted.removeKeys(keywords.context)
    }

    "be converted to compacted form without @id" in {
      val compacted = json"""{"@type": "Person", "name": "Me"}""".addContext(context.contextObj)

      val expanded = ExpandedJsonLd(compacted).accepted
      val result   = expanded.toCompacted(context).accepted
      result.rootId shouldEqual expanded.rootId
      result.json.removeKeys(keywords.context) shouldEqual compacted.removeKeys(keywords.context)
    }

    "be empty" in {
      ExpandedJsonLd(json"""[{"@id": "$example/id", "a": "b"}]""").accepted.isEmpty shouldEqual true
    }

    "not be empty" in {
      ExpandedJsonLd(compacted).accepted.isEmpty shouldEqual false
    }

    "be converted to graph" in {
      val expanded = ExpandedJsonLd(compacted).accepted
      val graph    = expanded.toGraph.rightValue
      val expected = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.rootNode shouldEqual iri
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "add @id value" in {
      val expanded = ExpandedJsonLd.expanded(json"""[{"@id": "$iri"}]""").rightValue
      val friends  = vocab + "friends"
      val batman   = base + "batman"
      val robin    = base + "robin"
      expanded.add(friends, batman).add(friends, robin).json shouldEqual
        json"""[{"@id": "$iri", "$friends": [{"@id": "$batman"}, {"@id": "$robin"} ] } ]"""
    }

    "add @type Iri to existing @type" in {
      val (person, animal, hero) = (schema.Person, schema + "Animal", schema + "Hero")
      val expanded               = ExpandedJsonLd.expanded(json"""[{"@id": "$iri", "@type": ["$person", "$animal"] } ]""").rightValue
      expanded
        .addType(hero)
        .addType(hero)
        .json shouldEqual json"""[{"@id": "$iri", "@type": ["$person", "$animal", "$hero"] } ]"""
    }

    "add @type Iri" in {
      val expanded = ExpandedJsonLd.expanded(json"""[{"@id": "$iri"}]""").rightValue
      expanded.addType(schema.Person).json shouldEqual json"""[{"@id": "$iri", "@type": ["${schema.Person}"] } ]"""
    }

    "add @value value" in {
      val expanded                       = ExpandedJsonLd.expanded(json"""[{"@id": "$iri"}]""").rightValue
      val tags                           = vocab + "tags"
      val (tag1, tag2, tag3, tag4, tag5) = ("first", 2, false, 30L, 3.14)
      expanded
        .add(tags, tag1)
        .add(tags, tag2)
        .add(tags, tag3)
        .add(tags, tag4)
        .add(tags, tag5)
        .json shouldEqual
        json"""[{"@id": "$iri", "$tags": [{"@value": "$tag1"}, {"@value": $tag2 }, {"@value": $tag3}, {"@value": $tag4}, {"@value": $tag5 } ] } ]"""
    }

    "fail when there are remote cyclic references" in {
      val contexts: Map[Iri, ContextValue]                   =
        Map(
          iri"http://localhost/c" -> ContextValue(json"""["http://localhost/d", {"c": "http://localhost/c"} ]"""),
          iri"http://localhost/d" -> ContextValue(json"""["http://localhost/e", {"d": "http://localhost/d"} ]"""),
          iri"http://localhost/e" -> ContextValue(json"""["http://localhost/c", {"e": "http://localhost/e"} ]""")
        )
      implicit val remoteResolution: RemoteContextResolution = RemoteContextResolution.fixed(contexts.toSeq: _*)

      val input =
        json"""{"@context": ["http://localhost/c", {"a": "http://localhost/a"} ], "a": "A", "c": "C", "d": "D"}"""
      ExpandedJsonLd(input).rejected shouldEqual RemoteContextCircularDependency(iri"http://localhost/c")
    }
  }
}
