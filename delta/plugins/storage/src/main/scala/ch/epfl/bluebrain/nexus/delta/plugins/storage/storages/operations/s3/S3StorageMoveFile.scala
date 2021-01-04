package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.MoveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection.UnsupportedOperation
import monix.bio.IO

object S3StorageMoveFile extends MoveFile {

  override def apply(sourcePath: Uri.Path, description: FileDescription): IO[MoveFileRejection, FileAttributes] =
    IO.raiseError(UnsupportedOperation(StorageType.S3Storage))
}
