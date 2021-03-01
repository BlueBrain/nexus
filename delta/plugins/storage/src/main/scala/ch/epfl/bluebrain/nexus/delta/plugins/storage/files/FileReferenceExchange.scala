package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRef}
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * File specific [[ReferenceExchange]] implementation.
  *
  * @param files the files module
  */
class FileReferenceExchange(files: Files)(implicit
    config: StorageTypeConfig,
    base: BaseUri,
    cr: RemoteContextResolution
) extends ReferenceExchange {
  override type A = File

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(files.fetch(IriSegment(iri), project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(files.fetchAt(IriSegment(iri), project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(files.fetchBy(IriSegment(iri), project, tag))
    }

  override def apply(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    schema.original match {
      case schemas.files => apply(project, reference)
      case _             => UIO.pure(None)
    }

  private def resourceToValue(
      resourceIO: IO[FileRejection, FileResource]
  ): UIO[Option[ReferenceExchangeValue[File]]] = {
    resourceIO
      .map { res =>
        Some(
          new ReferenceExchangeValue[File](
            toResource = res,
            toSource = res.value.asJson,
            toCompacted = res.toCompactedJsonLd,
            toExpanded = res.toExpandedJsonLd,
            toNTriples = res.toNTriples,
            toDot = res.toDot
          )
        )
      }
      .onErrorHandle(_ => None)
  }
}
