package ch.epfl.bluebrain.nexus.delta.service.acls

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclEvent, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import io.circe.syntax._
import monix.bio.{IO, UIO}

/**
  * [[EventExchange]] implementation for acls.
  *
  * @param acls the acls module
  */
class AclEventExchange(acls: Acls)(implicit base: BaseUri) extends EventExchange {

  override type A = Acl
  override type E = AclEvent
  override type M = Acl.Metadata

  override def toJsonEvent(event: Event): Option[Aux[E]] =
    event match {
      case ev: AclEvent => Some(JsonValue(ev))
      case _            => None
    }

  override def toResource(
      event: Event,
      tag: Option[TagLabel]
  ): UIO[Option[EventExchange.EventExchangeValue[A, M]]] = (event, tag) match {
    case (ev: AclEvent, None) => resourceToValue(acls.fetch(ev.address))
    case _                    => UIO.none
  }

  private def resourceToValue(
      resourceIO: IO[AclRejection, AclResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(res.value.metadata)))
      }
      .onErrorHandle(_ => None)
}
