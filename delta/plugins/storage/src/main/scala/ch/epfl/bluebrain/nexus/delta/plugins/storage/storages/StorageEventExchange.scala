package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageEvent, StorageRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * Storage specific [[EventExchange]] implementation.
  *
  * @param storages
  *   the storages module
  */
class StorageEventExchange(storages: Storages)(implicit base: BaseUri, crypto: Crypto) extends EventExchange {

  override type A = Storage
  override type E = StorageEvent
  override type M = Storage.Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: StorageEvent => Some(JsonValue(ev))
      case _                => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] =
    event match {
      case s: StorageEvent =>
        UIO.some(
          ProjectScopedMetric.from[StorageEvent](
            s,
            s match {
              case _: StorageCreated    => EventMetric.Created
              case _: StorageUpdated    => EventMetric.Updated
              case _: StorageTagAdded   => EventMetric.Tagged
              case _: StorageDeprecated => EventMetric.Deprecated
            },
            s.id,
            s.tpe.types,
            JsonObject.empty
          )
        )
      case _               => UIO.none
    }

  override def toResource(event: Event, tag: Option[UserTag]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: StorageEvent => resourceToValue(storages.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _                => UIO.none
    }

  private def resourceToValue(resourceIO: IO[StorageRejection, StorageResource])(implicit enc: JsonLdEncoder[A]) =
    resourceIO.map(Storages.eventExchangeValue).redeem(_ => None, Some(_))
}
