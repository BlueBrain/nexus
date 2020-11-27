package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.DecodingFailure
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, TestHelpers}
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
    val jsonLd = ExpandedJsonLd.expanded(json).rightValue
    val cursor = ExpandedJsonLdCursor(jsonLd)

    val drinks      = schema + "drinks"
    val alcohol     = schema + "alcohol"
    val ingredients = schema + "ingredients"
    val name        = schema + "name"
    val steps       = schema + "steps"
    val volume      = schema + "volume"
    val value       = schema + "value"

    "extract a String" in {
      cursor.downField(drinks).downField(name).getString.rightValue shouldEqual "Mojito"
    }

    "fail to extract a String" in {
      cursor.downField(drinks).downField(alcohol).getString.leftValue shouldEqual
        DecodingFailure(
          s"Could not extract a 'String' from the path 'DownArray,DownField($drinks),DownArray,DownField($alcohol),DownArray,DownField(@value)'"
        )
    }

    "extract a Boolean" in {
      cursor.downField(drinks).downField(alcohol).getBoolean.rightValue shouldEqual true
    }

    "fail to extract a Boolean" in {
      cursor.downField(drinks).downField(name).getBoolean.leftValue shouldEqual
        DecodingFailure(
          s"Could not convert 'Mojito' to 'boolean' from the path 'DownArray,DownField($drinks),DownArray,DownField($name),DownArray,DownField(@value)'"
        )
    }

    "extract a Double" in {
      cursor.downField(drinks).downField(volume).downField(value).getDouble.rightValue shouldEqual 8.3
    }

    "fail to extract a Double" in {
      cursor.downField(drinks).downField(ingredients).getDouble.leftValue shouldBe a[DecodingFailure]
    }

    "extract a Set of Strings" in {
      cursor.downField(drinks).downField(ingredients).values.rightValue.map(_.getString.rightValue) shouldEqual
        List("rum", "sugar")
    }

    "extract a List of Strings" in {
      cursor.downField(drinks).downField(steps).downList.values.rightValue.map(_.getString.rightValue) shouldEqual
        List("cut", "mix")
    }

    "fail to extract a List of Strings" in {
      cursor.downField(drinks).downField(ingredients).downList.values.leftValue shouldEqual
        DecodingFailure(
          s"Could not extract a 'Sequence' from the path 'DownArray,DownField($drinks),DownArray,DownField($ingredients),DownArray,DownField(@list)'"
        )
    }

    "extract the types" in {
      cursor.getTypes.rightValue shouldEqual Set(schema + "Menu", schema + "DrinkMenu")
      cursor.downField(drinks).getTypes.rightValue shouldEqual Set(schema + "Drink", schema + "Cocktail")
    }

    "fail to extract the types" in {
      cursor.downField(alcohol).getTypes.leftValue shouldEqual
        DecodingFailure(s"Could not extract a 'Set[Iri]' from the path 'DownArray,DownField($alcohol)'")
    }

  }

}
