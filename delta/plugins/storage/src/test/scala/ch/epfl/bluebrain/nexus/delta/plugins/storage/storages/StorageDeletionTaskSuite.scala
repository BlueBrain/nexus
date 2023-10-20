package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import fs2.Stream
import monix.bio.Task

import java.nio.file.Files

class StorageDeletionTaskSuite extends BioSuite with StorageFixtures {

  test("Delete content from local storage") {
    implicit val subject: Subject                 = Anonymous
    val project                                   = ProjectRef.unsafe("org", "proj")
    val storageStream: Stream[Task, StorageValue] =
      Stream(
        diskVal,
        s3Val,
        remoteVal
      )
    val storageDir                                = diskVal.rootDirectory(project)
    for {
      uuid        <- UUIDF.random()
      // We create the storage directory the same way as in real conditions, when the first
      // file is written
      (file, _)   <- DiskStorageSaveFile.initLocation(project, diskVal, uuid, "trace")
      _           <- Task.delay(Files.createFile(file))
      fileExists   = Task.delay(file.toFile.exists())
      _           <- fileExists.assert(true, s"'$file' should have been created.")
      deletionTask = new StorageDeletionTask(_ => storageStream)
      result      <- deletionTask(project)
      _            = assertEquals(result.log.size, 3, s"The three storages should have been processed:\n$result")
      _            = fileExists.assert(false, s"'$file' should have been deleted.")
      _            = assert(!storageDir.exists, s"The directory '$storageDir' should have been deleted.")

    } yield ()
  }
}
