package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.{ElasticSearchViewCreated, ElasticSearchViewDeprecated, ElasticSearchViewTagAdded, ElasticSearchViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchView, ElasticSearchViewEvent, ElasticSearchViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * ElasticSearchView specific [[EventExchange]] implementation.
  *
  * @param views
  *   the elasticsearch module
  */
class ElasticSearchViewEventExchange(views: ElasticSearchViews)(implicit base: BaseUri) extends EventExchange {

  override type A = ElasticSearchView
  override type E = ElasticSearchViewEvent
  override type M = ElasticSearchView.Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: ElasticSearchViewEvent => Some(JsonValue(ev))
      case _                          => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case e: ElasticSearchViewEvent =>
        UIO.some(
          ProjectScopedMetric.from[ElasticSearchViewEvent](
            e,
            e match {
              case _: ElasticSearchViewCreated    => EventMetric.Created
              case _: ElasticSearchViewUpdated    => EventMetric.Updated
              case _: ElasticSearchViewTagAdded   => EventMetric.Tagged
              case _: ElasticSearchViewDeprecated => EventMetric.Deprecated
            },
            e.id,
            e.tpe.types,
            JsonObject.empty
          )
        )
      case _                         => UIO.none
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: ElasticSearchViewEvent => resourceToValue(views.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _                          => UIO.none
    }

  private def resourceToValue(resourceIO: IO[ElasticSearchViewRejection, ViewResource])(implicit
      enc: JsonLdEncoder[A]
  ) =
    resourceIO.map(ElasticSearchViews.eventExchangeValue).redeem(_ => None, Some(_))
}
