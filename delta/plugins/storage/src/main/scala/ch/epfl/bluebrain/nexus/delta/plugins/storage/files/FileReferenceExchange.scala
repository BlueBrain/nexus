package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * File specific [[ReferenceExchange]] implementation.
  *
  * @param files the files module
  */
class FileReferenceExchange(files: Files)(implicit config: StorageTypeConfig) extends ReferenceExchange {

  override type A = File

  override def toResource(
      project: ProjectRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(files.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(files.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(files.fetchBy(iri, project, tag))
    }

  override def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]] =
    schema.original match {
      case schemas.files => toResource(project, reference)
      case _             => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[FileRejection, FileResource]
  )(implicit enc: JsonLdEncoder[A]): UIO[Option[ReferenceExchangeValue[A]]] =
    resourceIO
      .map(res => Some(ReferenceExchangeValue(res, res.value.asJson, enc)))
      .onErrorHandle(_ => None)
}
