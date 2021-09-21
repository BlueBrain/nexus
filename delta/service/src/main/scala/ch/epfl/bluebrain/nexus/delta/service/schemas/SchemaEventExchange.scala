package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent.{SchemaCreated, SchemaDeprecated, SchemaTagAdded, SchemaUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaEvent, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue, SchemaResource, Schemas}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * Schema specific [[EventExchange]] implementation.
  *
  * @param schemas
  *   the schemas module
  */
class SchemaEventExchange(schemas: Schemas)(implicit base: BaseUri) extends EventExchange {

  override type A = Schema
  override type E = SchemaEvent
  override type M = Unit

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: SchemaEvent => Some(JsonValue(ev))
      case _               => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case s: SchemaEvent =>
        UIO.some(
          ProjectScopedMetric.from[SchemaEvent](
            s,
            s match {
              case _: SchemaCreated    => EventMetric.Created
              case _: SchemaUpdated    => EventMetric.Updated
              case _: SchemaTagAdded   => EventMetric.Tagged
              case _: SchemaDeprecated => EventMetric.Deprecated
            },
            s.id,
            Set(nxv.Schema),
            JsonObject.empty
          )
        )
      case _              => UIO.none
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: SchemaEvent => resourceToValue(schemas.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _               => UIO.none
    }

  private def resourceToValue(resourceIO: IO[SchemaRejection, SchemaResource])(implicit enc: JsonLdEncoder[A]) =
    resourceIO.map(Schemas.eventExchangeValue).redeem(_ => None, Some(_))
}
