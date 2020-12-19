package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import monix.bio.IO

trait MoveFile {

  /**
    * Moves a file.
    *
    * @param sourcePath  the location of the file to be moved
    * @param description the end location of the file with its metadata
    */
  def apply(sourcePath: Uri.Path, description: FileDescription): IO[MoveFileRejection, FileAttributes]
}
