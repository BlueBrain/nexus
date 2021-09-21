package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, EventExchange, JsonValue, Resources}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * Resource specific [[EventExchange]] implementation.
  *
  * @param resources
  *   the resources module
  */
class ResourceEventExchange(resources: Resources)(implicit base: BaseUri) extends EventExchange {

  override type A = Resource
  override type E = ResourceEvent
  override type M = Unit

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: ResourceEvent => Some(JsonValue(ev))
      case _                 => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case r: ResourceEvent =>
        UIO.some(
          ProjectScopedMetric.from[ResourceEvent](
            r,
            r match {
              case _: ResourceCreated    => EventMetric.Created
              case _: ResourceUpdated    => EventMetric.Updated
              case _: ResourceTagAdded   => EventMetric.Tagged
              case _: ResourceDeprecated => EventMetric.Deprecated
            },
            r.id,
            r.types,
            JsonObject.empty
          )
        )
      case _                => UIO.none
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: ResourceEvent => resourceToValue(resources.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project, None))
      case _                 => UIO.none
    }

  private def resourceToValue(resourceIO: IO[ResourceRejection, DataResource])(implicit enc: JsonLdEncoder[A]) =
    resourceIO.map(Resources.eventExchangeValue).redeem(_ => None, Some(_))
}
