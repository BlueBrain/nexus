package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.defaultPrinter
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.{At, Start}
import io.circe.{Encoder, Json}
import io.circe.syntax.EncoderOps
import monix.bio.UIO

final class SseConverter[E] private (sseEncoder: SseEncoder[E], injector: Json => UIO[Json])(implicit
    base: BaseUri,
    jo: JsonKeyOrdering
) {

  implicit val circeEncoder: Encoder.AsObject[E] = sseEncoder(base)

  def apply(envelope: Envelope[_, E]): UIO[ServerSentEvent] =
    injector(envelope.value.asJson).map { json =>
      val data = defaultPrinter.print(json.sort)
      envelope.offset match {
        case Start     =>
          ServerSentEvent(data, envelope.tpe.value)
        case At(value) =>
          ServerSentEvent(data, envelope.tpe.value, value.toString)
      }
    }
}

object SseConverter {

  def apply[E](sseEncoder: SseEncoder[E])(implicit base: BaseUri, jo: JsonKeyOrdering) =
    new SseConverter[E](sseEncoder, UIO.pure)

  def apply[E](sseEncoder: SseEncoder[E], injector: Json => UIO[Json])(implicit base: BaseUri, jo: JsonKeyOrdering) =
    new SseConverter[E](sseEncoder, injector)

}
