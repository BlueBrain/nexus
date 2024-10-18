package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.{HttpEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileDelegationRequest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FormDataExtractor, UploadedFileInformation}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

object FileWiring {

  private val noFileOperationError = IO.raiseError(new IllegalArgumentException("FileOperations should not be called"))

  def noFileOperations: FileOperations = new FileOperations {
    override def save(
        storage: Storage,
        info: UploadedFileInformation,
        contentLength: Option[Long]
    ): IO[FileStorageMetadata] = ???

    override def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource] = noFileOperationError

    override def legacyLink(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] =
      noFileOperationError

    override def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes] =
      noFileOperationError

    override def delegate(storage: Storage, filename: String): IO[FileDelegationRequest.TargetLocation] =
      noFileOperationError
  }

  def failingFormDataExtractor: FormDataExtractor =
    (_: HttpEntity, _: Long) => IO.raiseError(new IllegalArgumentException("FormDataExtractor should not be called"))

}
