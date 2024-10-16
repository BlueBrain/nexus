package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access.DiskStorageAccess.checkVolumeExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.nio.file.{Files, Path}

class DiskStorageAccessSuite extends NexusSuite {

  test("succeed verifying the volume") {
    val volume = AbsolutePath(Files.createTempDirectory("disk-access")).rightValue
    checkVolumeExists(volume)
  }

  test("fail when volume does not exist") {
    val volume        = AbsolutePath(Path.of("/random", genString())).rightValue
    val expectedError = StorageNotAccessible(s"Volume '$volume' does not exist.")
    checkVolumeExists(volume).interceptEquals(expectedError)
  }

  test("fail when volume is not a directory") {
    val volume        = AbsolutePath(Files.createTempFile(genString(), genString())).rightValue
    val expectedError = StorageNotAccessible(s"Volume '$volume' is not a directory.")
    checkVolumeExists(volume).interceptEquals(expectedError)
  }

  test("fail when volume does not have write access") {
    val volume        = AbsolutePath(Files.createTempDirectory("disk-not-access")).rightValue
    volume.value.toFile.setReadOnly()
    val expectedError = StorageNotAccessible(s"Volume '$volume' does not have write access.")
    checkVolumeExists(volume).interceptEquals(expectedError)
  }
}
