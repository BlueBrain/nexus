package ch.epfl.bluebrain.nexus.delta.service.realms

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{Realm, RealmEvent, RealmRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import io.circe.syntax._
import monix.bio.{IO, UIO}

/**
  * [[EventExchange]] implementation for realms.
  *
  * @param realms
  *   the realms module
  */
class RealmEventExchange(realms: Realms)(implicit base: BaseUri) extends EventExchange {

  override type A = Realm
  override type E = RealmEvent
  override type M = Realm.Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: RealmEvent => Some(JsonValue(ev))
      case _              => None
    }

  // TODO: Implement in further development
  override def toMetric(event: Event): UIO[Option[EventMetric]] = UIO.none

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    (event, tag) match {
      case (ev: RealmEvent, None) => resourceToValue(realms.fetch(ev.label))
      case _                      => UIO.none
    }

  private def resourceToValue(resourceIO: IO[RealmRejection, RealmResource])(implicit
      enc: JsonLdEncoder[A]
  ) =
    resourceIO
      .map { res =>
        Some(
          EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(res.value.metadata), None)
        )
      }
      .onErrorHandle(_ => None)
}
