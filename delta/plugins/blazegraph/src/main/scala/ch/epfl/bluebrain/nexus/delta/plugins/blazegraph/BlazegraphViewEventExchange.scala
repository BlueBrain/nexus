package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue}
import monix.bio.{IO, UIO}

/**
  * BlazegraphView specific [[EventExchange]] implementation.
  *
  * @param views the blazegraph module
  */
class BlazegraphViewEventExchange(views: BlazegraphViews)(implicit base: BaseUri) extends EventExchange {

  override type A = BlazegraphView
  override type E = BlazegraphViewEvent
  override type M = BlazegraphView.Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: BlazegraphViewEvent => Some(JsonValue(ev))
      case _                       => None
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: BlazegraphViewEvent => resourceToValue(views.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _                       => UIO.none
    }

  private def resourceToValue(resourceIO: IO[BlazegraphViewRejection, ViewResource])(implicit enc: JsonLdEncoder[A]) =
    resourceIO.map(BlazegraphViews.eventExchangeValue).redeem(_ => None, Some(_))
}
