package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, Storage}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import monix.bio.{IO, UIO}

/**
  * Storage specific [[ReferenceExchange]] implementation.
  *
  * @param storages the storage module
  */
class StorageReferenceExchange(storages: Storages)(implicit base: BaseUri, cr: RemoteContextResolution, crypto: Crypto)
    extends ReferenceExchange {
  override type A = Storage

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[Storage]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(storages.fetch(IriSegment(iri), project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(storages.fetchAt(IriSegment(iri), project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(storages.fetchBy(IriSegment(iri), project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[Storage]]] =
    schema.original match {
      case schemas.storage => apply(project, reference)
      case _               => UIO.pure(None)
    }

  private def resourceToValue(
      resourceIO: IO[StorageFetchRejection, StorageResource]
  ): UIO[Option[ReferenceExchangeValue[Storage]]] = {
    resourceIO
      .map { res =>
        val secret = res.value.source
        Storage.encryptSource(secret, crypto).toOption.map { source =>
          new ReferenceExchangeValue[Storage](
            toResource = res,
            toSource = source,
            toCompacted = res.toCompactedJsonLd,
            toExpanded = res.toExpandedJsonLd,
            toNTriples = res.toNTriples,
            toDot = res.toDot
          )
        }
      }
      .onErrorHandle(_ => None)
  }
}
