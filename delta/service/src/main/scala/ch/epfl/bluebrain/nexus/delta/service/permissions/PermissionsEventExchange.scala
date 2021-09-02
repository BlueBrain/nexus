package ch.epfl.bluebrain.nexus.delta.service.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue.Aux
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{PermissionSet, PermissionsEvent, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * [[EventExchange]] implementation for permissions.
  *
  * @param permissions
  *   the permissions module
  */
class PermissionsEventExchange(permissions: Permissions)(implicit base: BaseUri) extends EventExchange {

  override type A = PermissionSet
  override type E = PermissionsEvent
  override type M = Unit

  override def toJsonEvent(event: Event): Option[Aux[E]] = event match {
    case ev: PermissionsEvent => Some(JsonValue(ev))
    case _                    => None
  }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchange.EventExchangeValue[A, M]]] =
    (event, tag) match {
      case (_: PermissionsEvent, None) => resourceToValue(permissions.fetch)
      case _                           => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[PermissionsRejection, PermissionsResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(())))
      }
      .onErrorHandle(_ => None)
}
