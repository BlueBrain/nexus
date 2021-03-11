package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileEvent, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceRef}
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * File specific [[ReferenceExchange]] implementation.
  *
  * @param files the files module
  */
class FileReferenceExchange(files: Files)(implicit config: StorageTypeConfig) extends ReferenceExchange {

  override type E = FileEvent
  override type A = File

  override def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]] =
    reference match {
      case ResourceRef.Latest(iri)           => resourceToValue(files.fetch(iri, project))
      case ResourceRef.Revision(_, iri, rev) => resourceToValue(files.fetchAt(iri, project, rev))
      case ResourceRef.Tag(_, iri, tag)      => resourceToValue(files.fetchBy(iri, project, tag))
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

  override def apply(event: Event): Option[(ProjectRef, Iri)] =
    event match {
      case value: FileEvent => Some((value.project, value.id))
      case _                => None
    }

  private def resourceToValue(resourceIO: IO[FileRejection, FileResource]): UIO[Option[ReferenceExchangeValue[File]]] =
    resourceIO
      .map { res => Some(ReferenceExchangeValue(res, res.value.asJson)) }
      .onErrorHandle(_ => None)
}
