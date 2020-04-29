package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.iri.Curie.Prefix
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.NodeValueArray
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.ExpandedTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{NodeObject, keyword}
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
        base = Val(base),
        vocab = Val(vocab),
        prefixMappings = Map(Prefix("xsd").rightValue -> Uri(xsd.base).rightValue),
        terms = Map(
          "typemap"   -> Some(ExpandedTermDefinition(vocabUri("some/typemap"), container = Set(tpe))),
          "list2"     -> Some(ExpandedTermDefinition(vocabUri("list2"), container = Set(keyword.list), tpe = Some(id))),
          "idmap"     -> Some(ExpandedTermDefinition(vocabUri("idmap"), container = Set(id))),
          "emptySet"  -> Some(ExpandedTermDefinition(vocabUri("emptySet"), container = Set(set))),
          "emptyList" -> Some(ExpandedTermDefinition(vocabUri("emptyList"), container = Set(keyword.list)))
        ),
        keywords = Map(tpe -> Set("type"), id -> Set("id"))
      )
    val definition = ExpandedTermDefinition(id = uri"http://example.com/id", context = Val(ctx))
    val cursor     = TermDefinitionCursor(Val(definition))
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

    "return invalid format on parsing" in {
      forAll(invalid) { json => NodeObjectParser(json, cursor).leftValue shouldBe a[InvalidObjectFormat] }
    }

    "return null on parsing" in {
      forAll(nullValueObject) { json => NodeObjectParser(json, cursor).leftValue shouldBe a[NullObject.type] }
    }

    "return not matched on parsing" in {
      forAll(notNodeObject) { json => NodeObjectParser(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
    }
  }

}
