package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.SaveFileRejection
import monix.bio.IO

trait SaveFile {

  /**
    * Saves a file with the passed ''description'' and ''source//.
    *
    * @param description the file description
    * @param source      the file stream
    */
  def apply(description: FileDescription, source: AkkaSource): IO[SaveFileRejection, FileAttributes]
}
