package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.{IO, UIO}

import java.nio.file.Files

object DiskStorageAccess extends StorageAccess {
  override type Storage = DiskStorageValue

  override def apply(id: Iri, storage: DiskStorageValue): IO[StorageNotAccessible, Unit] = {

    def failWhen(condition: Boolean, err: => String) =
      if (condition) IO.raiseError(StorageNotAccessible(id, err))
      else IO.unit

    for {
      exists      <- UIO.delay(Files.exists(storage.volume.value))
      _           <- failWhen(!exists, s"Volume '${storage.volume.value}' does not exist.")
      isDirectory <- UIO.delay(Files.isDirectory(storage.volume.value))
      _           <- failWhen(!isDirectory, s"Volume '${storage.volume.value}' is not a directory.")
      isWritable  <- UIO.delay(Files.isWritable(storage.volume.value))
      _           <- failWhen(!isWritable, s"Volume '${storage.volume.value}' does not have write access.")
    } yield ()
  }
}
