package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.HttpEntity
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FormDataExtractor
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring.{failingDiskFileOperations, failingRemoteDiskFileOperations, linkS3FileOperationOnly}

object FileWiring {

  def linkOperationOnly(s3StorageClient: S3StorageClient): FileOperations =
    FileOperations.mk(
      failingDiskFileOperations,
      failingRemoteDiskFileOperations,
      linkS3FileOperationOnly(s3StorageClient)
    )

  def failingFormDataExtractor: FormDataExtractor =
    (_: IriOrBNode.Iri, _: HttpEntity, _: Long) =>
      IO.raiseError(new IllegalArgumentException("FormDataExtractor should not be called"))

}
