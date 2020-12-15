package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.RemoteContextCircularDependency
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.VectorMap

class ExpandedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "An expanded Json-LD" should {
    val example          = "http://example.com"
    val compacted        = jsonContentOf("compacted.json")
    val context          = compacted.topContextValueOrEmpty
    val expectedExpanded = jsonContentOf("expanded.json")

    "be constructed successfully" in {
      ExpandedJsonLd(compacted).accepted shouldEqual ExpandedJsonLd.expanded(expectedExpanded).rightValue
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

    "be constructed empty (ignoring @id)" in {
      val compacted = json"""{"@id": "$iri"}"""
      val expanded  = ExpandedJsonLd(compacted).accepted
      expanded.json shouldEqual json"""[ {} ]"""
      expanded.rootId shouldBe a[BNode]
    }

    "be constructed with multiple root objects" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"$example/batman"
      val john      =
        json"""{"@id": "$iri", "@type": ["$example/Person"], "$example/name": [{"@value": "John"} ] }""".asObject.value
      val batman    =
        json"""{"@id": "$batmanIri", "@type": ["$example/Person", "$example/Hero"], "$example/name": [{"@value": "Batman"} ] }""".asObject.value

      ExpandedJsonLd(multiRoot).accepted shouldEqual ExpandedJsonLd(iri, VectorMap(iri -> john, batmanIri -> batman))
    }

    "change its root object" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"$example/batman"
      val john      =
        json"""{"@id": "$iri", "@type": ["$example/Person"], "$example/name": [{"@value": "John"} ] }""".asObject.value
      val batman    =
        json"""{"@id": "$batmanIri", "@type": ["$example/Person", "$example/Hero"], "$example/name": [{"@value": "Batman"} ] }""".asObject.value
      val expanded  = ExpandedJsonLd(multiRoot).accepted
      expanded.changeRootIfExists(batmanIri).value shouldEqual
        ExpandedJsonLd(batmanIri, VectorMap(batmanIri -> batman, iri -> john))
      expanded.changeRootIfExists(schema.base) shouldEqual None
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

    "remove a key" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"$example/batman"
      val name      = iri"$example/name"
      val json      =
        json"""[{"@id": "$iri", "@type": ["$example/Person"]}, {"@id": "$batmanIri", "@type": ["$example/Person", "$example/Hero"], "$name": [{"@value": "Batman"} ] }]"""

      ExpandedJsonLd(multiRoot).accepted.remove(name) shouldEqual ExpandedJsonLd.expanded(json).rightValue
    }

    "remove a key from all entries" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"$example/batman"
      val name      = iri"$example/name"
      val json      =
        json"""[{"@id": "$iri", "@type": ["$example/Person"]}, {"@id": "$batmanIri", "@type": ["$example/Person", "$example/Hero"] }]"""

      ExpandedJsonLd(multiRoot).accepted.removeFromEntries(name) shouldEqual ExpandedJsonLd.expanded(json).rightValue
    }

    "filter type" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"$example/batman"
      val name      = iri"$example/name"
      val json      =
        json"""[{"@id": "$batmanIri", "@type": ["$example/Person", "$example/Hero"], "$name": [{"@value": "Batman"}] }]"""

      val expanded = ExpandedJsonLd(multiRoot).accepted
      expanded.filterType(iri"$example/Hero") shouldEqual ExpandedJsonLd.expanded(json).rightValue

      expanded.filterType(iri"$example/Other") shouldEqual ExpandedJsonLd.empty

      expanded.filterType(iri"$example/Person") shouldEqual expanded
    }

    "fail when there are remote cyclic references" in {
      val contexts: Map[Iri, Json]                           =
        Map(
          iri"http://localhost/c" -> json"""{"@context": ["http://localhost/d", {"c": "http://localhost/c"} ] }""",
          iri"http://localhost/d" -> json"""{"@context": ["http://localhost/e", {"d": "http://localhost/d"} ] }""",
          iri"http://localhost/e" -> json"""{"@context": ["http://localhost/c", {"e": "http://localhost/e"} ] }"""
        )
      implicit val remoteResolution: RemoteContextResolution = RemoteContextResolution.fixed(contexts.toSeq: _*)

      val input =
        json"""{"@context": ["http://localhost/c", {"a": "http://localhost/a"} ], "a": "A", "c": "C", "d": "D"}"""
      ExpandedJsonLd(input).rejected shouldEqual RemoteContextCircularDependency(iri"http://localhost/c")
    }
  }
}
