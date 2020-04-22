package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.DirectionValue
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.LanguageMap
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.ExpandedTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.literal._

class LanguageMapSpec extends RdfSpec {

  private val ex = uri"http://example.com/ex"

  implicit private def toDirectionString(str: String): DirectionValue = (str, None)

  private def withDirection(v: DirectionValue, direction: Option[String]): DirectionValue = (v._1, direction)

  "A LanguageMap" should {
    val ctx        = Context(terms = Map("ex" -> ex), keywords = Map(language -> Set("language")))
    val definition = ExpandedTermDefinition(ex, context = Val(ctx), container = Set(language))
    val cursor     = TermDefinitionCursor(Val(definition))
    val en         = LanguageTag("en").rightValue
    val de         = LanguageTag("de").rightValue
    val ltr        = Val("ltr")

    val valid = List(
      json"""{"en": "The Queen", "de": ["Die Königin", "Ihre Majestät"]}""" ->
        LanguageMap(Map(en -> Vector("The Queen"), de -> Vector("Die Königin", "Ihre Majestät"))),
      json"""{"@none": ["The Queen"], "de": ["Die Königin", "Ihre Majestät"]}""" ->
        LanguageMap(Map(de -> Vector("Die Königin", "Ihre Majestät")), Vector("The Queen")),
      json"""{"en": null, "de": ["Die Königin", "Ihre Majestät"]}""" ->
        LanguageMap(Map(de -> Vector("Die Königin", "Ihre Majestät"))),
      json"""{"@none": "The Queen"}""" ->
        LanguageMap(others = Vector("The Queen")),
      json"""{}""" -> LanguageMap()
    )

    val invalid = List(
      json"""{"en": {"@value": "The Queen"}}""",
      json"""{"en": 1}""",
      json"""{"wrong!language": "The value"}"""
    )

    val notMatched = List(
      json"""[{"en": "The Queen"}]""",
      json"""1""",
      json""""string""""
    )

    val nullLanguageMap = List(json"""null""")

    "be parsed" in {
      forAll(valid) {
        case (json, value) => LanguageMapParser(json, cursor).rightValue shouldEqual value
      }
    }

    "be parsed with default direction" in {
      val otherCursor = TermDefinitionCursor(Val(definition.copy(direction = ltr)))
      forAll(valid) {
        case (json, value) =>
          LanguageMapParser(json, otherCursor).rightValue shouldEqual
            LanguageMap(
              value.value.view.mapValues(_.map(withDirection(_, ltr.toOption))).toMap,
              value.others.map(withDirection(_, ltr.toOption))
            )
      }
    }

    "return invalid format on parsing" in {
      forAll(invalid) { json => LanguageMapParser(json, cursor).leftValue shouldBe a[InvalidObjectFormat] }
    }

    "return null on parsing" in {
      forAll(nullLanguageMap) { json => LanguageMapParser(json, cursor).leftValue shouldBe a[NullObject.type] }
    }

    "return not matched on parsing" in {
      forAll(notMatched) { json => LanguageMapParser(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
      val otherCursor = TermDefinitionCursor(Val(definition.copy(container = Set(index))))
      forAll(valid.map { case (json, _) => json } ++ invalid ++ notMatched) { json =>
        LanguageMapParser(json, otherCursor).leftValue shouldBe a[NotMatchObject.type]
      }
    }
  }

}
