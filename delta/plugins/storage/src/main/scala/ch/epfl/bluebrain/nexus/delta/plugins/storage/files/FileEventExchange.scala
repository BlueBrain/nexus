package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileEvent, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, IdSegmentRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonLdValue, JsonValue}
import io.circe.syntax._
import monix.bio.{IO, UIO}

/**
  * File specific [[EventExchange]] implementation.
  *
  * @param files the files module
  */
class FileEventExchange(files: Files)(implicit base: BaseUri, config: StorageTypeConfig) extends EventExchange {

  override type A = File
  override type E = FileEvent
  override type M = File

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: FileEvent => Some(JsonValue(ev))
      case _             => None
    }

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    event match {
      case ev: FileEvent => resourceToValue(files.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project))
      case _             => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[FileRejection, FileResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(res.value)))
      }
      .onErrorHandle(_ => None)
}
