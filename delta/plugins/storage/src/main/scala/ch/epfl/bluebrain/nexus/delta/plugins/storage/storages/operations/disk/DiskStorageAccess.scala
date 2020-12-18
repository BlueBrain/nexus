package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

import java.nio.file.Files

object DiskStorageAccess extends StorageAccess {
  override type Storage = DiskStorageValue

  override def apply(id: Iri, storage: DiskStorageValue): IO[StorageNotAccessible, Unit] = {

    def failWhen(condition: Boolean, err: => String) =
      if (condition) IO.raiseError(StorageNotAccessible(id, err))
      else IO.unit

    for {
      exists      <- IO.delay(Files.exists(storage.volume)).hideErrors
      _           <- failWhen(!exists, s"Volume '${storage.volume}' does not exist.")
      isDirectory <- IO.delay(Files.isDirectory(storage.volume)).hideErrors
      _           <- failWhen(!isDirectory, s"Volume '${storage.volume}' is not a directory.")
      isWritable  <- IO.delay(Files.isWritable(storage.volume)).hideErrors
      _           <- failWhen(!isWritable, s"Volume '${storage.volume}' does not have write access.")
    } yield ()
  }
}
