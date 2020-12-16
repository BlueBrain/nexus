package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}

class DiskStorageAccessSpec extends AnyWordSpecLike with Matchers with IOValues with TestHelpers {

  "A DiskStorage access operations" should {
    val iri = iri"http://localhost/disk"

    "succeed verifying the volume" in {
      val volume = Files.createTempDirectory("disk-access")
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, 10)
      DiskStorageAccess(iri, value).accepted
    }

    "fail when volume does not exist" in {
      val volume = Path.of("random", genString())
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, 10)
      DiskStorageAccess(iri, value).rejected shouldEqual StorageNotAccessible(iri, s"Volume '$volume' does not exist.")
    }

    "fail when volume is not a directory" in {
      val volume = Files.createTempFile(genString(), genString())
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, 10)
      DiskStorageAccess(iri, value).rejected shouldEqual
        StorageNotAccessible(iri, s"Volume '$volume' is not a directory.")
    }

    "fail when volume does not have write access" in {
      val volume = Files.createTempDirectory("disk-not-access")
      volume.toFile.setReadOnly()
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, 10)
      DiskStorageAccess(iri, value).rejected shouldEqual
        StorageNotAccessible(iri, s"Volume '$volume' does not have write access.")
    }
  }

}
