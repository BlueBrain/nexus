package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, Storage, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef}
import monix.bio.{IO, UIO}

/**
  * Storage specific [[ReferenceExchange]] implementation.
  *
  * @param storages the storage module
  */
class StorageReferenceExchange(storages: Storages)(implicit crypto: Crypto) extends ReferenceExchange {

  override type E = StorageEvent
  override type A = Storage
  override type M = Metadata

  override def apply(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Storage, Metadata]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(storages.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(storages.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(storages.fetchBy(iri, project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Storage, Metadata]]] =
    schema.original match {
      case schemas.storage => apply(project, reference)
      case _               => UIO.pure(None)
    }

  override def apply(event: Event): Option[(ProjectRef, Iri)] =
    event match {
      case value: StorageEvent => Some((value.project, value.id))
      case _                   => None
    }

  private def resourceToValue(
      resourceIO: IO[StorageFetchRejection, StorageResource]
  ): UIO[Option[ReferenceExchangeValue[Storage, Metadata]]] =
    resourceIO
      .map { res =>
        val secret = res.value.source
        Storage.encryptSource(secret, crypto).toOption.map(source => ReferenceExchangeValue(res, source)(_.metadata))
      }
      .onErrorHandle(_ => None)
}
