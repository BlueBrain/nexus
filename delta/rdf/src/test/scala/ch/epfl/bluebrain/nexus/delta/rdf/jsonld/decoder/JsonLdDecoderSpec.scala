package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.{DecodingDerivationFailure, ParsingFailure}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderSpec.Drink.{Cocktail, Volume}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderSpec.Menu.DrinkMenu
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderSpec.{AbsoluteIri, Drink, Menu}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn
import scala.concurrent.duration._

@nowarn("cat=unused")
class JsonLdDecoderSpec extends CatsEffectSpec with Fixtures {

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
              steps = List("cut", "mix"),
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
      implicit val drinkDecoder: JsonLdDecoder[Drink] = deriveDefaultJsonLdDecoder[Drink]
      implicit val menuDecoder: JsonLdDecoder[Menu]   = deriveDefaultJsonLdDecoder[Menu]
      jsonLd.to[Menu].leftValue shouldBe a[DecodingDerivationFailure]
    }

    "fail decoding a relative iri in @id value position" in {
      val json                                         = jsonContentOf("/jsonld/decoder/relative-iri.json")
      val jsonLd                                       = ExpandedJsonLd(json).accepted
      val context                                      = jsonContentOf("/jsonld/decoder/relative-iri-context.json")
      val ctx                                          = JsonLdContext(context.topContextValueOrEmpty).accepted
      implicit val config: Configuration               = Configuration.default.copy(context = ctx)
      implicit val decoder: JsonLdDecoder[AbsoluteIri] = deriveConfigJsonLdDecoder[AbsoluteIri]
      jsonLd.to[AbsoluteIri].leftValue shouldBe a[ParsingFailure]
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
        steps: List[String],
        volume: Volume,
        instant: Instant,
        uuid: UUID,
        hangover: FiniteDuration
    ) extends Drink

    final case class OtherDrink(id: Iri, name: String) extends Drink

    final case class Volume(unit: String, value: Double)
  }

  final case class AbsoluteIri(value: Iri)
}
