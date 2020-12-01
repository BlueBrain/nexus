package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure.KeyMissingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.{DecodingFailure, ParsingFailure}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, TestHelpers}
import io.circe.CursorOp.{DownArray, DownField}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ExpandedJsonLdCursorSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with EitherValuable
    with TestHelpers {

  "An ExpandedJsonLdCursor" should {
    val json   = jsonContentOf("/jsonld/decoder/cocktail.json")
    val cursor = ExpandedJsonLd.expanded(json).rightValue.cursor

    val drinks      = schema + "drinks"
    val alcohol     = schema + "alcohol"
    val ingredients = schema + "ingredients"
    val name        = schema + "name"
    val steps       = schema + "steps"
    val volume      = schema + "volume"
    val value       = schema + "value"

    "fail to extract a missing key" in {
      val missing = schema + "xxx"
      cursor.downField(drinks).downField(missing).get[String].leftValue shouldEqual
        KeyMissingFailure(
          "@value",
          List(DownField("http://schema.org/xxx"), DownArray, DownField("http://schema.org/drinks"), DownArray)
        )
    }

    "extract a String" in {
      cursor.downField(drinks).downField(name).get[String].rightValue shouldEqual "Mojito"
    }

    "fail to extract a String" in {
      val c = cursor.downField(drinks).downField(alcohol)
      forAll(List(c.get[String], c.getOrElse("default"))) { result =>
        result.leftValue shouldEqual
          ParsingFailure(
            s"Could not extract a 'String' from the path 'DownArray,DownField($drinks),DownArray,DownField($alcohol),DownArray,DownField(@value)'"
          )
      }

    }

    "extract default value" in {
      cursor.downField(alcohol).getOrElse("default").rightValue shouldEqual "default"
    }

    "extract a Boolean" in {
      cursor.downField(drinks).downField(alcohol).get[Boolean].rightValue shouldEqual true
    }

    "fail to extract a Boolean" in {
      cursor.downField(drinks).downField(name).get[Boolean].leftValue shouldEqual
        ParsingFailure(
          s"Could not convert 'Mojito' to 'boolean' from the path 'DownArray,DownField($drinks),DownArray,DownField($name),DownArray,DownField(@value)'"
        )
    }

    "extract a Double" in {
      cursor.downField(drinks).downField(volume).downField(value).get[Double].rightValue shouldEqual 8.3
    }

    "fail to extract a Double" in {
      cursor.downField(drinks).downField(ingredients).get[Double].leftValue shouldBe a[DecodingFailure]
    }

    "extract a Set of Strings" in {
      cursor.downField(drinks).downField(ingredients).get[Set[String]].rightValue shouldEqual Set("rum", "sugar")
    }

    "extract a List of Strings" in {
      cursor.downField(drinks).downField(steps).get[List[String]].rightValue shouldEqual List("cut", "mix")
    }

    "fail to extract a List of Strings" in {
      cursor.downField(drinks).downField(ingredients).get[List[String]].leftValue shouldEqual
        ParsingFailure(
          s"Could not extract a 'Sequence' from the path 'DownArray,DownField($drinks),DownArray,DownField($ingredients),DownArray,DownField(@list)'"
        )
    }

    "extract the types" in {
      cursor.getTypes.rightValue shouldEqual Set(schema + "Menu", schema + "DrinkMenu")
      cursor.downField(drinks).getTypes.rightValue shouldEqual Set(schema + "Drink", schema + "Cocktail")
    }

    "fail to extract the types" in {
      cursor.downField(alcohol).getTypes.leftValue shouldEqual
        ParsingFailure(s"Could not extract a 'Set[Iri]' from the path 'DownArray,DownField($alcohol)'")
    }

  }

}
