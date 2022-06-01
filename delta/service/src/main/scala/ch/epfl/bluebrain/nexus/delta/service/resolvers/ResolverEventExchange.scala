package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Resolver, ResolverEvent, ResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue, ResolverResource, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * Resolver specific [[EventExchange]] implementation.
  *
  * @param resolvers
  *   the resolvers module
  */
class ResolverEventExchange(resolvers: Resolvers)(implicit base: BaseUri) extends EventExchange {

  override type A = Resolver
  override type E = ResolverEvent
  override type M = Unit

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: ResolverEvent => Some(JsonValue(ev))
      case _                 => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case r: ResolverEvent =>
        UIO.some(
          ProjectScopedMetric.from[ResolverEvent](
            r,
            r match {
              case _: ResolverCreated    => EventMetric.Created
              case _: ResolverUpdated    => EventMetric.Updated
              case _: ResolverTagAdded   => EventMetric.Tagged
              case _: ResolverDeprecated => EventMetric.Deprecated
            },
            r.id,
            r.tpe.types,
            JsonObject.empty
          )
        )
      case _                => UIO.none
    }

  override def toResource(event: Event, tag: Option[UserTag]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: ResolverEvent => resourceToValue(resolvers.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _                 => UIO.none
    }

  private def resourceToValue(resourceIO: IO[ResolverRejection, ResolverResource])(implicit enc: JsonLdEncoder[A]) =
    resourceIO.map(Resolvers.eventExchangeValue).redeem(_ => None, Some(_))
}
