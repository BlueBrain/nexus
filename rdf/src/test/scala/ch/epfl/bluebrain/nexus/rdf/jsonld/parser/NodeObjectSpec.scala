package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.iri.Curie.Prefix
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.NodeValueArray
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr.{Empty, Val}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.ExpandedTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{JsonLD, NodeObject, keyword}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.literal._

class NodeObjectSpec extends RdfSpec {

  implicit private def toValueObject(str: String): ValueObject                = ValueObject(Literal(str))
  implicit private def toValueObjectNode(node: NodeObject): WrappedNodeObject = WrappedNodeObject(node)

  "A NodeObject" should {
    val vocab                         = uri"http://example.com/vocab/"
    val base                          = uri"http://example.com/base/"
    def vocabUri(suffix: String): Uri = uri"${vocab.iriString}$suffix"
    def baseUri(suffix: String): Uri  = uri"${base.iriString}$suffix"
    val ctx =
      Context(
        base = Some(base),
        vocab = Some(vocab),
        prefixMappings = Map(Prefix("xsd").rightValue -> Uri(xsd.base).rightValue),
        terms = Map(
          "typemap"   -> ExpandedTermDefinition(vocabUri("some/typemap"), container = Set(tpe)),
          "list2"     -> ExpandedTermDefinition(vocabUri("list2"), container = Set(keyword.list), tpe = Some(id)),
          "idmap"     -> ExpandedTermDefinition(vocabUri("idmap"), container = Set(id)),
          "emptySet"  -> ExpandedTermDefinition(vocabUri("emptySet"), container = Set(set)),
          "emptyList" -> ExpandedTermDefinition(vocabUri("emptyList"), container = Set(keyword.list))
        ),
        keywords = Map(tpe -> Set("type"), id -> Set("id"))
      )
    val definition = ExpandedTermDefinition(id = uri"http://example.com/id", context = Val(ctx))
    val cursor = TermDefinitionCursor(Val(definition))
    val bob        = NodeObject(id = Some(baseUri("bob")), terms = Vector(vocabUri("name") -> "Bob"))
    val alice      = NodeObject(id = Some(baseUri("alice")), terms = Vector(vocabUri("name") -> "Alice"))
    val other      = NodeObject(id = Some(baseUri("other")), terms = Vector(vocabUri("name") -> "Other"))
    val v          = vocabUri("v")
    val list       = ListValue(Vector(NodeValueArray("one item")))
    val list2      = ListValue(Vector(NodeObject(id = Some(baseUri("a"))), NodeObject(id = Some(baseUri("b")))))
    val foo        = uri"http://ex.org/foo"
    val tpeMap = TypeMap(
      Map(
        foo -> bob.copy(id = None, types = Vector(foo)),
        v   -> alice.copy(id = None, types = Vector(v))
      )
    )
    val idMap = IdMap(Map(baseUri("foo") -> Vector(other.copy(id = Some(baseUri("foo"))))))
    val valid = List(
      json"""{"field": [null]}""" -> NodeObject(terms = Vector(vocabUri("field") -> SetValue())),
      json"""{"know": [{"@id": "bob", "name": "Bob"}, {"@id": "alice", "name": {"@value": "Alice"} } ] }""" ->
        NodeObject(terms = Vector(vocabUri("know") -> SetValue(Vector(bob, alice)))),
      json"""{"@id": "id", "@type": ["A", "B"], "name": "Bob", "list": {"@list": "one item"}, "list2": ["a", "b"] }""" ->
        bob.copy(
          id = Some(baseUri("id")),
          types = Vector(vocabUri("A"), vocabUri("B")),
          terms = bob.terms ++ Vector(vocabUri("list") -> list, vocabUri("list2") -> list2)
        ),
      json"""{"@id": "id", "field": {"@context": null, "@id": "http://ex.org/id", "name": "ignore"}}""" ->
        NodeObject(
          id = Some(baseUri("id")),
          terms = Vector(vocabUri("field") -> NodeObject(Some(uri"http://ex.org/id")))
        ),
      json"""{"@id": "id", "field": {"@context": {"A": "http://ex.org/AB"},"@type": "A"}}""" ->
        NodeObject(
          id = Some(baseUri("id")),
          terms = Vector(vocabUri("field") -> NodeObject(types = Vector(uri"http://ex.org/AB")))
        ),
      json"""{"typemap": {"http://ex.org/foo": {"name": "Bob"}, "v": {"name": "Alice"} }, "idmap": {"foo": {"name": "Other"} } }""" ->
        NodeObject(terms = Vector(vocabUri("some/typemap") -> tpeMap, vocabUri("idmap") -> idMap)),
      json"""{"emptyList": [ null ], "emptySet": [null], "emptySet2": {"@set": [null]}, "emptySet3": {"@set": [null]} , "emptySet4": []}""" ->
        NodeObject(terms =
          Vector(
            vocabUri("emptyList") -> ListValue(),
            vocabUri("emptySet")  -> SetValue(),
            vocabUri("emptySet2") -> SetValue(),
            vocabUri("emptySet3") -> SetValue(),
            vocabUri("emptySet4") -> SetValue()
          )
        ),
      json"""{"@id": "http://ex.com/id", "field": {"@language": "en"}, "name": "Bob" }""" ->
        NodeObject(id = Some(uri"http://ex.com/id"), terms = Vector(vocabUri("name") -> "Bob"))
    )

    val invalid = List(
      json"""{"@id": "a", "id": "b"}"""
    )

    val notNodeObject = List(
      json"""[true, false]""",
      json"""{"@list": []}""",
      json"""{"@value": 1}""",
      json""""string""""
    )

    val nullValueObject = List(
      json"""null"""
    )

    "be parsed" in {
      forAll(valid) {
        case (json, value) =>
          NodeObjectParser(json, cursor).rightValue shouldEqual value
      }
    }

    "be parsed with multiple scoped contexts" ignore {
      val json = json"""{"@context": {"@base": "http://example/base-base/", "@vocab": "http://example/", "foo": "http://example/foo", "Type": {"@context": {"@base": "http://example/typed-base/", "xsd": "http://xsd.com/xsd/", "@vocab": "http://type-vocab/", "nestedscoped": {"@context": {"@base": "http://nestedscoped.com/base/", "foo": "http://replaced.com/foo"}, "@id": "http://nestedscoped-id.com/nestedscoped"} } }, "Type2": {"@context": {"@base": "http://example/type2base/", "@vocab": "http://type-2-vocab/"} } }, "@id": "base-id", "@type": "Type2", "p": {"@id": "typed-id", "@type": "Type", "nestedNode": {"@id": "nested-id", "foo": "bar"}, "nestedscoped": {"@id": "nested-id-scoped", "foo": "bar"}, "subjectReference": [{"@id": "subject-reference-id"}, {"@id": "subject-reference-id-2"} ], "valuereferece": {"@value": "1", "@type": "xsd:xsd"} } }"""
      val result = JsonLD.expand(json).rightValue
      val expected = json"""[{"@id": "http://example/type2base/base-id", "@type": ["http://example/Type2"], "http://type-2-vocab/p": [{"@id": "http://example/typed-base/typed-id", "@type": ["http://example/Type"], "http://type-vocab/nestedNode": [{"@id": "http://example/base-base/nested-id", "http://example/foo": [{"@value": "bar"} ] } ], "http://nestedscoped-id.com/nestedscoped": [{"@id": "http://nestedscoped.com/base/nested-id-scoped", "http://replaced.com/foo": [{"@value": "bar"} ] } ], "http://type-vocab/subjectReference": [{"@id": "http://example/typed-base/subject-reference-id"}, {"@id": "http://example/typed-base/subject-reference-id-2"} ], "http://type-vocab/valuereferece": [{"@type": "http://xsd.com/xsd/xsd", "@value": "1"} ] } ] } ]"""
      println(result.toJson())
      println("=====")
      println(expected)
      result.toJson() shouldEqual expected
    }

    "return invalid format on parsing" in {
      forAll(invalid) { json => NodeObjectParser(json, cursor).leftValue shouldBe a[InvalidObjectFormat] }
    }

    "return invalid format on parsing when expanded id" in {
      val jsons = List(json"""{"@id": "a"}""", json"""{"@type": "a"}""")
      forAll(jsons) { json => NodeObjectParser(json, TermDefinitionCursor(Empty)).leftValue shouldBe a[InvalidObjectFormat] }
    }

    "return null on parsing" in {
      forAll(nullValueObject) { json => NodeObjectParser(json, cursor).leftValue shouldBe a[NullObject.type] }
    }

    "return not matched on parsing" in {
      forAll(notNodeObject) { json => NodeObjectParser(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
    }
  }

}
