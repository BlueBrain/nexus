package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, EventExchange, JsonLdValue, Resources}
import monix.bio.{IO, UIO}

/**
  * Resource specific [[EventExchange]] implementation.
  *
  * @param resources the resources module
  */
class ResourceEventExchange(resources: Resources)(implicit base: BaseUri) extends EventExchange {

  override type A = Resource
  override type E = ResourceEvent
  override type M = Unit

  override def toJsonLdEvent(event: Event): Option[Aux[E]] =
    event match {
      case ev: ResourceEvent => Some(JsonLdValue(ev))
      case _                 => None
    }

  override def toLatestResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: ResourceEvent =>
        val res = tag.fold(resources.fetch(ev.id, ev.project, None))(resources.fetchBy(ev.id, ev.project, None, _))
        resourceToValue(res)
      case _                 => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[ResourceRejection, DataResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(())))
      }
      .onErrorHandle(_ => None)
}
