package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

class TimesN(times: Int) extends Pipe {
  override type In  = Int
  override type Out = Int
  override def label: Label           = TimesN.label
  override def inType: Typeable[Int]  = Typeable[Int]
  override def outType: Typeable[Int] = Typeable[Int]

  override def apply(element: SuccessElem[Int]): Task[Elem[Int]] =
    Task.pure(element.success(element.value * times))
}

object TimesN extends PipeDef {
  override type PipeType = TimesN
  override type Config   = TimesNConfig
  override def configType: Typeable[Config]               = Typeable[TimesNConfig]
  override def configDecoder: JsonLdDecoder[TimesNConfig] = JsonLdDecoder[TimesNConfig]
  override def label: Label                               = Label.unsafe("times-n")
  override def withConfig(config: TimesNConfig): TimesN   = new TimesN(config.times)

  final case class TimesNConfig(times: Int) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          BNode.random,
          JsonObject(
            (nxv + "times").toString -> Json.arr(Json.obj("@value" -> Json.fromInt(times)))
          )
        )
      )
    )
  }

  object TimesNConfig {
    implicit val timesNConfigDecoder: JsonLdDecoder[TimesNConfig] =
      deriveJsonLdDecoder[TimesNConfig]
  }
}
