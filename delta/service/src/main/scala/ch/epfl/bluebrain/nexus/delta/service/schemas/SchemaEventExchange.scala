package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaEvent, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue, SchemaResource, Schemas}
import monix.bio.{IO, UIO}

/**
  * Schema specific [[EventExchange]] implementation.
  *
  * @param schemas the schemas module
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

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: SchemaEvent => resourceToValue(schemas.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _               => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[SchemaRejection, SchemaResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(Schemas.eventExchangeValue(res))
      }
      .onErrorHandle(_ => None)
}
