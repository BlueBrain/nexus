package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaEvent, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonLdValue, SchemaResource, Schemas}
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

  override def toJsonLdEvent(event: Event): Option[Aux[E]] =
    event match {
      case ev: SchemaEvent => Some(JsonLdValue(ev))
      case _               => None
    }

  override def toLatestResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: SchemaEvent =>
        resourceToValue(tag.fold(schemas.fetch(ev.id, ev.project))(schemas.fetchBy(ev.id, ev.project, _)))
      case _               => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[SchemaRejection, SchemaResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(())))
      }
      .onErrorHandle(_ => None)
}
