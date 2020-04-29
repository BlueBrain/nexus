package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.iri.Curie.Prefix
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.ValueObject
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.ExpandedTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, EmptyNullOr}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.literal._

class ValueObjectSpec extends RdfSpec {

  "A ValueObject" should {
    val en  = Some(LanguageTag("en").rightValue)
    val de  = Some(LanguageTag("de").rightValue)
    val ltr = Val("ltr")
    val rtl = Val("rtl")
    val ex  = uri"http://example.com/ex"
    val ctx =
      Context(
        prefixMappings = Map(Prefix("xsd").rightValue -> Uri(xsd.base).rightValue),
        terms = Map("ex"                              -> Some(ex)),
        keywords = Map(keyword.tpe                    -> Set("type"), keyword.value -> Set("value"))
      )
    val definition = ExpandedTermDefinition(ex, context = Val(ctx))
    val cursor     = TermDefinitionCursor(Val(definition))
    // format: off
    val valid = List(
      json"""2"""                                                                                 -> ValueObject(Literal(2)),
      json"""[2.12]"""                                                                            -> ValueObject(Literal(2.12)),
      json"""true"""                                                                              -> ValueObject(Literal(true)),
      json"""{"@value": true, "@type": "xsd:boolean"}"""                                          -> ValueObject(Literal(true), explicitType = Some(xsd.boolean)),
      json""""string""""                                                                          -> ValueObject(Literal("string")),
      json"""["string"]"""                                                                        -> ValueObject(Literal("string")),
      json"""{"value": "text", "type": "ex", "ignored": "whatever"}"""                            -> ValueObject(Literal("text"), explicitType = Some(ex)),
      json"""{"value": "text", "type": "ex", "ex": null}"""                                       -> ValueObject(Literal("text"), explicitType = Some(ex)),
      json"""{"@value": "text", "@language": "en"}"""                                             -> ValueObject(Literal("text", xsd.string, en)),
      json"""{"@value": "text", "@language": "en", "@direction": "ltr"}"""                        -> ValueObject(Literal("text", xsd.string, en), direction = ltr.toOption),
      json"""[{"@value": "text", "@language": "en", "@direction": "ltr", "@index": "someIdx"}]""" -> ValueObject(Literal("text", xsd.string, en), direction = ltr.toOption, index = Some("someIdx"))
    )
    // format: on

    val invalid = List(
      json"""{"@value": "text", "value": "text2", "@type": "ex"}""",
      json"""{"value": "text", "ex": "value2"}""",
      json"""{"value": {"@id": "text"}, "type": "ex"}""",
      json"""{"value": ["text"], "type": "ex"}""",
      json"""{"@value": "text", "@type": "ex", "@language": "en"}""",
      json"""{"@value": true, "@type": "notexistingalias"}""",
      json"""{"@value": "text", "@language": "wrong-@lang"}""",
      json"""{"@value": "text", "@id": "http://example.com/id"}""",
      json"""{"@value": "text", "@direction": "invalid"}"""
    )

    val notValueObject = List(
      json"""[null]""",
      json"""[true, false]""",
      json"""{"@id": "http://example.com/id"}""",
      json"""{"@list": []}"""
    )

    val nullValueObject = List(
      json"""null""",
      json"""{"@value": null}""",
      json"""{"@language": "en"}"""
    )

    "be parsed" in {
      forAll(valid) {
        case (json, value) => ValueObjectParser(json, cursor).rightValue shouldEqual value
      }
    }

    "be parsed with custom context datatype" in {
      val jsons       = List(json"1" -> Literal(1), json"true" -> Literal(true), json"5.1" -> Literal(5.1))
      val custom      = uri"http://example.com/custom"
      val otherCursor = TermDefinitionCursor(Val(ExpandedTermDefinition(ex, tpe = Some(custom))))
      forAll(jsons) {
        case (json, lit) =>
          ValueObjectParser(json, otherCursor).rightValue shouldEqual
            ValueObject(lit, explicitType = Some(custom))
      }
    }

    "be parsed with language on term definition" in {
      val otherCursor =
        TermDefinitionCursor(Val(ExpandedTermDefinition(ex, context = Val(ctx), language = EmptyNullOr(de))))
      val jsons = List(
        json""""string""""                               -> ValueObject(Literal("string", xsd.string, de)),
        json"""["string"]"""                             -> ValueObject(Literal("string", xsd.string, de)),
        json"""{"value": "string", "@language": "en"}""" -> ValueObject(Literal("string", xsd.string, en))
      )
      forAll(jsons) {
        case (json, value) => ValueObjectParser(json, otherCursor).rightValue shouldEqual value
      }
    }

    "be parsed with direction on term definition overriding context" in {
      val otherCursor =
        TermDefinitionCursor(Val(ExpandedTermDefinition(ex, context = Val(ctx.copy(direction = rtl)), direction = ltr)))
      val jsons = List(
        json""""string""""                               -> ValueObject(Literal("string"), direction = ltr.toOption),
        json"""["string"]"""                             -> ValueObject(Literal("string"), direction = ltr.toOption),
        json"""{"value": "string", "@language": "en"}""" -> ValueObject(Literal("string", xsd.string, en))
      )
      forAll(jsons) {
        case (json, value) => ValueObjectParser(json, otherCursor).rightValue shouldEqual value
      }
    }

    "return invalid format on parsing" in {
      forAll(invalid) { json => ValueObjectParser(json, cursor).leftValue shouldBe a[InvalidObjectFormat] }
    }

    "return null on parsing" in {
      forAll(nullValueObject) { json => ValueObjectParser(json, cursor).leftValue shouldBe a[NullObject.type] }
    }

    "return not matched on parsing" in {
      forAll(notValueObject) { json => ValueObjectParser(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
    }

    "return not matched when term definition has @type: @id" in {
      val otherCursor = TermDefinitionCursor(Val(definition.copy(tpe = Some(id))))
      val jsons       = List(json"""["string"]""", json""""string"""")
      forAll(jsons) { json => ValueObjectParser(json, otherCursor).leftValue shouldBe a[NotMatchObject.type] }
    }

    "be parsed when term definition has @type: @id but term overrides @type" in {
      val json        = json"""{"value": "text", "@type": "ex"}"""
      val otherCursor = TermDefinitionCursor(Val(definition.copy(tpe = Some(id))))

      ValueObjectParser(json, otherCursor).rightValue shouldEqual ValueObject(
        Literal("text", xsd.string),
        explicitType = Some(ex)
      )
    }

    "be parsed ignoring @type in term definition when is a json object" in {
      val json        = json"""{"value": "text"}"""
      val otherCursor = TermDefinitionCursor(Val(definition.copy(tpe = Some(ex))))
      ValueObjectParser(json, otherCursor).rightValue shouldEqual ValueObject(Literal("text"))
    }
  }

}
