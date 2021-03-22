package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, Storage}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.{IO, UIO}

/**
  * Storage specific [[ReferenceExchange]] implementation.
  *
  * @param storages the storage module
  */
class StorageReferenceExchange(storages: Storages)(implicit crypto: Crypto) extends ReferenceExchange {

  override type A = Storage

  override def toResource(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Storage]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(storages.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(storages.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(storages.fetchBy(iri, project, tag))
    }

  override def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Storage]]] =
    schema.original match {
      case schemas.storage => toResource(project, reference)
      case _               => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[StorageFetchRejection, StorageResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[ReferenceExchangeValue[Storage]]] =
    resourceIO
      .map { res =>
        val secret = res.value.source
        Storage.encryptSource(secret, crypto).toOption.map(source => ReferenceExchangeValue(res, source, enc))
      }
      .onErrorHandle(_ => None)
}
