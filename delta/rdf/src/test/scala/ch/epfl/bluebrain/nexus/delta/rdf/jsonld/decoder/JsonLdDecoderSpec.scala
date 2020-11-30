package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.DecodingDerivationFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderSpec.Drink.{Cocktail, Volume}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderSpec.Menu.DrinkMenu
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderSpec.{Drink, Menu}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.annotation.nowarn
import scala.concurrent.duration._

@nowarn("cat=unused")
class JsonLdDecoderSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with EitherValuable
    with IOValues
    with TestHelpers {

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed()

  "A JsonLdDecoder" should {

    val json                                          = jsonContentOf("/jsonld/decoder/cocktail.json")
    val jsonLd                                        = ExpandedJsonLd(json).accepted
    val context                                       = jsonContentOf("/jsonld/decoder/context.json")
    val ctx                                           = JsonLdContext(context.topContextValueOrEmpty).accepted
    implicit val config: Configuration                = Configuration.default.copy(context = ctx)
    implicit val volumeDecoder: JsonLdDecoder[Volume] = deriveConfigJsonLdDecoder[Volume]

    "decode a Menu" in {
      implicit val drinkDecoder: JsonLdDecoder[Drink] = deriveConfigJsonLdDecoder[Drink]
      implicit val menuDecoder: JsonLdDecoder[Menu]   = deriveConfigJsonLdDecoder[Menu]

      jsonLd.to[Menu].rightValue shouldEqual
        DrinkMenu(
          Set(
            Cocktail(
              schema + "mojito",
              alcohol = true,
              ing = Set("rum", "sugar"),
              name = "Mojito",
              steps = NonEmptyList.of("cut", "mix"),
              volume = Volume("%", 8.3),
              link = Some(schema + "mylink"),
              instant = Instant.parse("2020-11-25T21:29:38.939939Z"),
              uuid = UUID.fromString("e45e15a9-5e4c-4485-8de4-1a011d12349c"),
              hangover = 1.hour
            )
          )
        )
    }

    "fail decoding a Menu" in {
      implicit val drinkDecoder: JsonLdDecoder[Drink] = deriveJsonLdDecoder[Drink]
      implicit val menuDecoder: JsonLdDecoder[Menu]   = deriveJsonLdDecoder[Menu]
      jsonLd.to[Menu].leftValue shouldBe a[DecodingDerivationFailure]
    }
  }

}
object JsonLdDecoderSpec {
  sealed trait Menu  extends Product with Serializable
  sealed trait Drink extends Product with Serializable
  object Menu  {
    final case class DrinkMenu(drinks: Set[Drink]) extends Menu
    final case class FoodMenu(name: String)        extends Menu
  }
  object Drink {
    final case class Cocktail(
        id: Iri,
        alcohol: Boolean,
        ing: Set[String],
        name: String = "other",
        description: Option[String] = Some("default description"),
        link: Option[Iri] = Some(schema.Person),
        steps: NonEmptyList[String],
        volume: Volume,
        instant: Instant,
        uuid: UUID,
        hangover: FiniteDuration
    ) extends Drink

    final case class OtherDrink(id: Iri, name: String) extends Drink

    final case class Volume(unit: String, value: Double)
  }
}
