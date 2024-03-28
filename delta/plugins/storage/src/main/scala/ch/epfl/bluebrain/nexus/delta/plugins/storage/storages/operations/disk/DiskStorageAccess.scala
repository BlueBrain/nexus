package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue

import java.nio.file.Files

object DiskStorageAccess {

  def apply(storage: DiskStorageValue): IO[Unit] = {

    def failWhen(condition: Boolean, err: => String) = {
      IO.raiseWhen(condition)(StorageNotAccessible(err))
    }

    for {
      exists      <- IO.blocking(Files.exists(storage.volume.value))
      _           <- failWhen(!exists, s"Volume '${storage.volume.value}' does not exist.")
      isDirectory <- IO.blocking(Files.isDirectory(storage.volume.value))
      _           <- failWhen(!isDirectory, s"Volume '${storage.volume.value}' is not a directory.")
      isWritable  <- IO.blocking(Files.isWritable(storage.volume.value))
      _           <- failWhen(!isWritable, s"Volume '${storage.volume.value}' does not have write access.")
    } yield ()
  }
}
