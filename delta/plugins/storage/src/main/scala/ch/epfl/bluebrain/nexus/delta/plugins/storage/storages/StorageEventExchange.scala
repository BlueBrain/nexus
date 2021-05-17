package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageEvent, StorageRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonLdValue, JsonValue}
import monix.bio.{IO, UIO}

/**
  * Storage specific [[EventExchange]] implementation.
  *
  * @param storages the storages module
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

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: StorageEvent =>
        resourceToValue(tag.fold(storages.fetch(ev.id, ev.project))(storages.fetchBy(ev.id, ev.project, _)))
      case _                => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[StorageRejection, StorageResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        val secret = res.value.source
        Storage
          .encryptSource(secret, crypto)
          .toOption
          .map(source => EventExchangeValue(ReferenceExchangeValue(res, source, enc), JsonLdValue(res.value.metadata)))
      }
      .onErrorHandle(_ => None)
}
