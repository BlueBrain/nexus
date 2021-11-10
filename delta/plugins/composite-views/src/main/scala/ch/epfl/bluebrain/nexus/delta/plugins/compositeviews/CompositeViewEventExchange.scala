package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent.{CompositeViewCreated, CompositeViewDeprecated, CompositeViewTagAdded, CompositeViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonLdValue, JsonValue}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * CompositeView specific [[EventExchange]] implementation.
  *
  * @param views
  *   the composite views module
  */
class CompositeViewEventExchange(views: CompositeViews)(implicit base: BaseUri) extends EventExchange {

  override type A = CompositeView
  override type E = CompositeViewEvent
  override type M = CompositeView.Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: CompositeViewEvent => Some(JsonValue(ev))
      case _                      => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case c: CompositeViewEvent =>
        UIO.some(
          ProjectScopedMetric.from[CompositeViewEvent](
            c,
            c match {
              case _: CompositeViewCreated    => EventMetric.Created
              case _: CompositeViewUpdated    => EventMetric.Updated
              case _: CompositeViewTagAdded   => EventMetric.Tagged
              case _: CompositeViewDeprecated => EventMetric.Deprecated
            },
            c.id,
            Set(nxv.View, compositeViewType),
            JsonObject.empty
          )
        )
      case _                     => UIO.none
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: CompositeViewEvent => resourceToValue(views.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _                      => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[CompositeViewRejection, ViewResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(
          EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(res.value.metadata), None)
        )
      }
      .onErrorHandle(_ => None)
}
