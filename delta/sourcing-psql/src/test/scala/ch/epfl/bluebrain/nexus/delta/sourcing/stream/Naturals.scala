package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.StreamSource
import fs2.Stream
import io.circe.{Json, JsonObject}
import monix.bio.Task

import java.time.Instant
import scala.concurrent.duration._

// Indirectly tests [[StreamSource]]
object Naturals {

  def apply(): SourceDef =
    StreamSource.configured[Int, NaturalsConfig](
      label,
      cfg =>
        offset => {
          def buildStream(start: Int): Stream[Task, Envelope[Iri, Int]] =
            Stream
              .iterate(start + 1)(_ + 1)
              .takeWhile(_ < cfg.max)
              .map(e => envelopeFor(e))
              .covary[Task] ++ Stream.sleep[Task](cfg.sleepBeforeLast).map(_ => envelopeFor(cfg.max))

          def envelopeFor(value: Int): Envelope[Iri, Int] =
            Envelope(
              tpe = EntityType("Int"),
              id = iri"http://localhost/$value",
              rev = 1,
              value = value,
              instant = Instant.now(),
              offset = Offset.at(value.toLong)
            )

          offset match {
            case Offset.Start     => buildStream(0)
            case Offset.At(value) => buildStream(value.toInt)
          }
        }
    )

  def label: Label         = Label.unsafe("naturals")
  def reference: SourceRef = SourceRef(label)

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
