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

class FailEveryN(failEvery: Int) extends Pipe {
  override type In  = String
  override type Out = String
  override def label: Label              = Log.label
  override def inType: Typeable[String]  = Typeable[String]
  override def outType: Typeable[String] = Typeable[String]

  private var countSinceLastFail: Int = 0

  override def apply(element: SuccessElem[String]): Task[Elem[String]] =
    if (countSinceLastFail == failEvery - 1)
      Task.delay {
        countSinceLastFail = 0
        element.failed(s"Fail every $failEvery elements")
      }
    else
      Task.delay {
        countSinceLastFail += 1
        element
      }
}

object FailEveryN extends PipeDef {
  override type PipeType = FailEveryN
  override type Config   = FailEveryNConfig
  override def configType: Typeable[FailEveryNConfig]           = Typeable[FailEveryNConfig]
  override def configDecoder: JsonLdDecoder[FailEveryNConfig]   = FailEveryNConfig.failEveryNConfigJsonLdDecoder
  override def label: Label                                     = Label.unsafe("fail-every-n")
  override def withConfig(config: FailEveryNConfig): FailEveryN = new FailEveryN(config.failEvery)

  final case class FailEveryNConfig(failEvery: Int) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          BNode.random,
          JsonObject(
            (nxv + "failEvery").toString -> Json.arr(Json.obj("@value" -> Json.fromInt(failEvery)))
          )
        )
      )
    )
  }

  object FailEveryNConfig {
    implicit val failEveryNConfigJsonLdDecoder: JsonLdDecoder[FailEveryNConfig] =
      deriveJsonLdDecoder[FailEveryNConfig]
  }
}
