package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.ListValueWrapper
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.{ListValue, SetValue, ValueObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.ExpandedTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus.{InvalidObjectFormat, NotMatchObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, NodeObject}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.literal._

class ArrayObjectSpec extends RdfSpec {

  implicit private[jsonld] def toArrayValue(string: String): ArrayEntry = ValueObject(Literal(string))
  implicit private[jsonld] def toArrayValue(int: Int): ArrayEntry       = ValueObject(Literal(int))
  implicit private[jsonld] def toArrayValue(bool: Boolean): ArrayEntry  = ValueObject(Literal(bool))
  private val ex                                                        = uri"http://example.com/ex"

  "A ListObject" should {
    val ctx =
      Context(terms = Map("ex" -> ex), keywords = Map(list -> Set("list"), keyword.value -> Set("value")))
    val cursor = TermDefinitionCursor(Val(ExpandedTermDefinition(ex, context = Val(ctx))))

    val valid = List(
      json"""[{"@list": ["one", null, "two"]}]"""                              -> ListValue(List("one", "two")),
      json"""[{"ignored": "value", "@list": [{"@value": 1}, {"value": 2}]}]""" -> ListValue(List(1, 2)),
      json"""[{"@list": []}]"""                                                -> ListValue(),
      json"""[{"@list": [null]}]"""                                            -> ListValue(),
      json"""{"@list":[null]}"""                                               -> ListValue(),
      json"""{"@list": "one"}"""                                               -> ListValue(List("one")),
      json"""{"list": ["one"], "@index": "myindex"}"""                         -> ListValue(List("one"), index = Some("myindex"))
    )

    val invalid = List(
      json"""{"@list": [], "@id": "http://example.com/id"}""",
      json"""{"@list": [], "ex": "http://example.com/id"}"""
    )

    val notList = List(
      json"""[{"@id": "http://example.com/id"}]""",
      json"""{"@id": "http://example.com/id"}""",
      json"""{"@set": "one"}""",
      json"""{"@value": 1}""",
      json""""string""""
    )

    "be parsed" in {
      forAll(valid) {
        case (json, value) =>
          ArrayObjectParser.listObject(json, cursor).rightValue shouldEqual value
      }
    }

    "return invalid format on parsing" in {
      forAll(invalid) { json => ArrayObjectParser.listObject(json, cursor).leftValue shouldBe a[InvalidObjectFormat] }
    }

    "return not matched on parsing" in {
      forAll(notList) { json => ArrayObjectParser.listObject(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
    }
  }

  "A SetObject" should {
    val ctx =
      Context(terms = Map("ex" -> ex), keywords = Map(set -> Set("set"), keyword.value -> Set("value")))
    val cursor = TermDefinitionCursor(Val(ExpandedTermDefinition(ex, context = Val(ctx))))

    val valid = List(
      json"""[{"@set": ["one", null, "two"]}]"""                              -> SetValue(Vector("one", "two")),
      json"""[{"ignored": "value", "@set": [{"@value": 1}, {"value": 2}]}]""" -> SetValue(Vector(1, 2)),
      json"""[{"@set": []}]"""                                                -> SetValue(),
      json"""[{"@set": [null]}]"""                                            -> SetValue(),
      json"""{"@set": "one"}"""                                               -> SetValue(Vector("one")),
      json"""{"set": ["one"], "@index": "myindex"}"""                         -> SetValue(Vector("one"), index = Some("myindex"))
    )

    val invalid = List(
      json"""{"@set": [], "@id": "http://example.com/id"}""",
      json"""{"@set": [], "ex": "http://example.com/id"}"""
    )

    val notSet = List(
      json"""[{"@id": "http://example.com/id"}]""",
      json"""{"@id": "http://example.com/id"}""",
      json"""[{"@list": []}]""",
      json"""{"@value": 1}""",
      json""""string""""
    )

    "be parsed" in {
      forAll(valid) {
        case (json, value) =>
          ArrayObjectParser.setObject(json, cursor).rightValue shouldEqual value
      }
    }

    "failed to parse" in {
      forAll(invalid) { json => ArrayObjectParser.setObject(json, cursor).leftValue }
    }

    "return not matched on parsing" in {
      forAll(notSet) { json => ArrayObjectParser.setObject(json, cursor).leftValue shouldBe a[NotMatchObject.type] }
    }
  }

  "A ListArray" should {
    val ctx        = Context(terms = Map("ex" -> ex), keywords = Map(keyword.value -> Set("value")))
    val definition = ExpandedTermDefinition(ex, context = Val(ctx), container = Set(list))
    val cursor     = TermDefinitionCursor(Val(definition))

    val valid = List(
      json"""["one", null, "two"]"""                -> ListValue(List("one", "two")),
      json""""string""""                            -> ListValue(List("string")),
      json"""1"""                                   -> ListValue(List(1)),
      json"""[{"@value": 1}, null, {"value": 2}]""" -> ListValue(List(1, 2)),
      json"""[]"""                                  -> ListValue(List()),
      json"""[null]"""                              -> ListValue(List()),
      json"""null"""                                -> ListValue(List()),
      json"""[{"@set": [1]}]"""                     -> ListValue(List(ListValueWrapper(ListValue(List(1))))),
      json"""[{"@list": [1]}]"""                    -> ListValue(List(ListValueWrapper(ListValue(List(1))))),
      json"""{"@id": "http://example.com/id"}"""    -> ListValue(List(NodeObject(id = Some(uri"http://example.com/id")))),
      json"""[["baz"]]"""                           -> ListValue(List(ListValueWrapper(ListValue(List("baz"))))),
      json"""[[]]"""                                -> ListValue(List(ListValueWrapper(ListValue(List()))))
    )

    val invalid = List(json"""{"@id": "a"}""")

    "be parsed" in {
      forAll(valid) {
        case (json, value) =>
          ArrayParser.list(json, cursor).rightValue shouldEqual value
      }
    }

    "failed to parse" in {
      forAll(invalid) { json => ArrayParser.list(json, cursor).leftValue }
    }

    "return not matched on parsing" in {
      val cursorNotList = TermDefinitionCursor(Val(definition.copy(container = Set(index))))
      forAll((valid.map { case (json, _) => json } ++ invalid).filterNot(_.isNull)) { json =>
        ArrayParser.list(json, cursorNotList).leftValue shouldBe a[NotMatchObject.type]
      }

    }

  }

  "A SetArray" should {
    val ctx        = Context(terms = Map("ex" -> ex), keywords = Map(keyword.value -> Set("value")))
    val definition = ExpandedTermDefinition(ex, context = Val(ctx), container = Set(set))
    val cursor     = TermDefinitionCursor(Val(definition))

    // format: off
    val valid = List(
      json"""["one", null, "two"]"""                                                    -> SetValue(Vector("one", "two")),
      json""""string""""                                                                -> SetValue(Vector("string")),
      json"""1"""                                                                       -> SetValue(Vector(1)),
      json"""[{"@value": 1}, null, {"value": 2}]"""                                     -> SetValue(Vector(1, 2)),
      json"""[]"""                                                                      -> SetValue(Vector()),
      json"""[null]"""                                                                  -> SetValue(Vector()),
      json"""[null, null]"""                                                            -> SetValue(Vector()),
      json"""null"""                                                                    -> SetValue(Vector()),
      json"""[{"@id": "http://example.com/id"}]"""                                      -> SetValue(Vector(NodeObject(id = Some(uri"http://example.com/id")))),
      json"""[{ "@set": []}, [], {"@set": [null]}, [null] ]"""                          -> SetValue(),
      json"""[{"@list": [1]},{"@set": [2]}]"""                                          -> SetValue(Vector(ListValueWrapper(ListValue(Vector(1))), 2)),
      json"""[{"@set": []}]"""                                                          -> SetValue(),
      json"""[{ "@set": ["hello", "this"]}, "will", {"@set": ["be", "collapsed"]}]"""   -> SetValue(Vector("hello", "this", "will", "be", "collapsed"))
    )
    // format: on

    val invalid = List(json"""{"@id": "id"}""")

    val notSet = List(json"""[{"@list": []}]""")

    "be parsed" in {
      forAll(valid) {
        case (json, value) =>
          ArrayParser.set(json, cursor).rightValue shouldEqual value
      }
    }

    "be parsed as @id" in {
      val json       = json"""["id", true, 0]"""
      val ctx        = Context(base = Some(uri"http://ex.com/base/"), vocab = Val(uri"http://ex.com/vocab/"))
      val cursorId   = TermDefinitionCursor(Val(ExpandedTermDefinition(ex, tpe = Some(id), context = Val(ctx))))
      val expectedId = SetValue(Vector(NodeObject(id = Some(uri"http://ex.com/base/id")), true, 0))
      ArrayParser.set(json, cursorId).rightValue shouldEqual expectedId
    }

    "be parsed as @value" in {
      val json   = json"""["id", true, 0]"""
      val ctx    = Context(base = Some(uri"http://ex.com/base/"), vocab = Val(uri"http://ex.com/vocab/"))
      val cursor = TermDefinitionCursor(Val(ExpandedTermDefinition(ex, context = Val(ctx))))
      ArrayParser.set(json, cursor).rightValue shouldEqual SetValue(Vector("id", true, 0))
    }

    "failed to parse" in {
      forAll(invalid) { json => ArrayParser.set(json, cursor).leftValue }
    }

    "return not matched on parsing" in {
      val cursorNotSet = TermDefinitionCursor(Val(definition.copy(container = Set(index))))

      forAll((valid.map { case (json, _) => json } ++ invalid ++ notSet).filterNot(_.isNull)) { json =>
        ArrayParser.list(json, cursorNotSet).leftValue shouldBe a[NotMatchObject.type]
      }
    }
  }

}
