package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewEvent, CompositeViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonLdValue}
import monix.bio.{IO, UIO}

/**
  * CompositeView specific [[EventExchange]] implementation.
  *
  * @param views the composite views module
  */
class CompositeViewEventExchange(views: CompositeViews)(implicit base: BaseUri) extends EventExchange {

  override type A = CompositeView
  override type E = CompositeViewEvent
  override type M = CompositeView.Metadata

  override def toJsonLdEvent(event: Event): Option[Aux[E]] =
    event match {
      case ev: CompositeViewEvent => Some(JsonLdValue(ev))
      case _                      => None
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: CompositeViewEvent =>
        resourceToValue(tag.fold(views.fetch(ev.id, ev.project))(views.fetchBy(ev.id, ev.project, _)))
      case _                      => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[CompositeViewRejection, ViewResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(res.value.metadata)))
      }
      .onErrorHandle(_ => None)
}
