package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.MediaTypeDetector
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileLinkRequest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.FetchStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.LinkFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.S3FileLink
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait LinkFileAction {

  def apply(storageIri: Option[Iri], project: ProjectRef, request: FileLinkRequest)(implicit
      caller: Caller
  ): IO[StorageWrite[FileAttributes]]
}

object LinkFileAction {

  val alwaysFails: LinkFileAction = new LinkFileAction {
    override def apply(storageIri: Option[Iri], project: ProjectRef, request: FileLinkRequest)(implicit
        caller: Caller
    ): IO[StorageWrite[FileAttributes]] = IO.raiseError(LinkFileRejection.Disabled)
  }

  def apply(
      fetchStorage: FetchStorage,
      mediaTypeDetector: MediaTypeDetector,
      s3FileOps: S3FileOperations
  ): LinkFileAction = apply(fetchStorage, mediaTypeDetector, s3FileOps.link(_, _))

  def apply(
      fetchStorage: FetchStorage,
      mediaTypeDetector: MediaTypeDetector,
      s3FileLink: S3FileLink
  ): LinkFileAction = new LinkFileAction {
    override def apply(storageIri: Option[Iri], project: ProjectRef, request: FileLinkRequest)(implicit
        caller: Caller
    ): IO[StorageWrite[FileAttributes]] =
      fetchStorage.onWrite(storageIri, project).flatMap {
        case (storageRef, storage: S3Storage) =>
          s3FileLink(storage.value.bucket, request.path).map { s3Metadata =>
            val contentType = mediaTypeDetector(s3Metadata.filename, request.mediaType, s3Metadata.mediaType)
            val attributes  =
              FileAttributes.from(s3Metadata.filename, contentType, request.metadata, s3Metadata.metadata)
            StorageWrite(storageRef, storage.tpe, attributes)
          }
        case (_, s)                           => IO.raiseError(LinkFileRejection.UnsupportedOperation(s.tpe))
      }
  }

}
