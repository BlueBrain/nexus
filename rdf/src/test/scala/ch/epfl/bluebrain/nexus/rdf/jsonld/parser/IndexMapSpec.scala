package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.ExpandedTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.literal._

class IndexMapSpec extends RdfSpec {

  private val ex = uri"http://example.com/ex"

  implicit private def literalStr(str: String): Literal    = Literal(str)
  implicit private def literalBool(bool: Boolean): Literal = Literal(bool)
  implicit private def literalInt(int: Int): Literal       = Literal(int)

  "An IndexMap" should {
    val ctx        = Context(terms = Map("ex" -> ex), keywords = Map(index -> Set("index")))
    val definition = ExpandedTermDefinition(ex, context = Val(ctx), container = Set(index))
    val cursor     = TermDefinitionCursor(Val(definition))
    val en         = Some(LanguageTag("en").rightValue)
    val id         = uri"http://ex.com/id"

    val valid = List(
      json"""{"A": [{"@id":"http://ex.com/id"}, true, null, "string", {"@value": "string en", "@language": "en", "@index": "other"}]}""" ->
        IndexMap(
          Map(
            "A" -> SetValue(
              Vector(
                NodeObject(id = Some(id), index = Some("A")),
                ValueObject(true, index = Some("A")),
                ValueObject("string", index = Some("A")),
                ValueObject(Literal("string en", xsd.string, en), index = Some("other"))
              )
            )
          )
        ),
      json"""{"A": "string", "B": [1, true], "C": {"@id":"http://ex.com/id", "@index": "D"}}""" ->
        IndexMap(
          Map(
            "A" -> ValueObject("string", index = Some("A")),
            "B" -> SetValue(Vector(ValueObject(1, index = Some("B")), ValueObject(Literal(true), index = Some("B")))),
            "C" -> WrappedNodeObject(NodeObject(id = Some(id), index = Some("D")))
          )
        )
    )

//    val invalid = List(
//      json"""{"en": {"@value": "The Queen"}}""",
//      json"""{"en": 1}""",
//      json"""{"wrong!language": "The value"}"""
//    )

    val notMatched = List(
      json"""[{"A": "The Queen"}]""",
      json"""1""",
      json""""string""""
    )

    val nullIndexMap = List(json"""null""")

    "be parsed" in {
      forAll(valid) {
        case (json, value) =>
          IndexMapParser(json, cursor).rightValue shouldEqual value
      }
    }

//    "return invalid format on parsing" in {
//      forAll(invalid) { json => IndexMapParser(json, Some(definition)).leftValue shouldBe a[InvalidObjectFormat] }
//    }

    "return null on parsing" in {
      forAll(nullIndexMap) { json => IndexMapParser(json, cursor).leftValue shouldBe a[NullObject.type] }
    }

    "return not matched on parsing" in {
      forAll(notMatched) { json => IndexMapParser(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
      val otherCursor = TermDefinitionCursor(Val(definition.copy(container = Set(graph))))
      forAll(valid.map { case (json, _) => json } ++ notMatched) { json =>
        IndexMapParser(json, otherCursor).leftValue shouldBe a[NotMatchObject.type]
      }
    }
  }

}
