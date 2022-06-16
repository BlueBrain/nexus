package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.SourceId
import fs2.Stream
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

import java.time.Instant
import scala.concurrent.duration._

class Naturals(override val id: Iri, max: Int, sleepBeforeLast: FiniteDuration) extends Source {
  require(max >= 0, "Max configuration must be a positive Integer")
  override type Out = Int
  override def label: Label           = Naturals.label
  override def outType: Typeable[Int] = Typeable[Int]

  override def apply(offset: ProjectionOffset): Stream[Task, Elem[Int]] =
    offset.forSource(this) match {
      case Offset.Start     => buildStream(0)
      case Offset.At(value) => buildStream(value.toInt)
    }

  private def buildStream(start: Int) =
    Stream
      .iterate(start + 1)(_ + 1)
      .takeWhile(_ < max)
      .map(e => elemFor(e))
      .covary[Task] ++ Stream.sleep[Task](sleepBeforeLast).map(_ => elemFor(max))

  private def elemFor(value: Int): Elem[Int] =
    SuccessElem[Int](
      SourceId(id),
      EntityType("Int"),
      iri"http://localhost/$value",
      1,
      Instant.now(),
      Offset.at(value.toLong),
      value
    )
}

object Naturals extends SourceDef {
  override type SourceType = Naturals
  override type Config     = NaturalsConfig
  override def configType: Typeable[NaturalsConfig]         = Typeable[NaturalsConfig]
  override def configDecoder: JsonLdDecoder[NaturalsConfig] = NaturalsConfig.naturalsConfigJsonLdDecoder
  override def label: Label                                 = Label.unsafe("naturals")

  override def withConfig(config: NaturalsConfig, id: Iri): Naturals =
    new Naturals(id, config.max, config.sleepBeforeLast)

  final case class NaturalsConfig(max: Int, sleepBeforeLast: FiniteDuration) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          BNode.random,
          JsonObject(
            (nxv + "max").toString             -> Json.arr(Json.obj("@value" -> Json.fromInt(max))),
            (nxv + "sleepBeforeLast").toString -> Json.arr(
              Json.obj("@value" -> Json.fromString(sleepBeforeLast.toString()))
            )
          )
        )
      )
    )
  }

  object NaturalsConfig {
    implicit val naturalsConfigJsonLdDecoder: JsonLdDecoder[NaturalsConfig] =
      deriveJsonLdDecoder[NaturalsConfig]
  }
}
