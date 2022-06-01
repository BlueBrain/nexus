package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.{EventExchangeResult, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceTagDeleted, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.JsonObject
import monix.bio.UIO

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
              case _: ResourceTagDeleted => EventMetric.TagDeleted
              case _: ResourceDeprecated => EventMetric.Deprecated
            },
            r.id,
            r.types,
            JsonObject.empty
          )
        )
      case _                => UIO.none
    }

  override def toResource(event: Event, tag: Option[UserTag]): UIO[Option[EventExchangeResult]] =
    event match {
      case ev: ResourceEvent =>
        resources
          .fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project, None)
          .map(Resources.eventExchangeValue)
          .redeem(_ => Some(TagNotFound(ev.id)), Some(_))
      case _                 => UIO.none
    }
}
