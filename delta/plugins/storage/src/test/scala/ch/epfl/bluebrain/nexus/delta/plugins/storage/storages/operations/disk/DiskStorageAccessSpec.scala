package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValues
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.BIOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}

class DiskStorageAccessSpec extends AnyWordSpecLike with Matchers with BIOValues with EitherValues with TestHelpers {

  "A DiskStorage access operations" should {
    val iri = iri"http://localhost/disk"

    "succeed verifying the volume" in {
      val volume = AbsolutePath(Files.createTempDirectory("disk-access")).rightValue
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, Some(100), 10)
      DiskStorageAccess(iri, value).accepted
    }

    "fail when volume does not exist" in {
      val volume = AbsolutePath(Path.of("/random", genString())).rightValue
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, Some(100), 10)
      DiskStorageAccess(iri, value).rejected shouldEqual StorageNotAccessible(iri, s"Volume '$volume' does not exist.")
    }

    "fail when volume is not a directory" in {
      val volume = AbsolutePath(Files.createTempFile(genString(), genString())).rightValue
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, Some(100), 10)
      DiskStorageAccess(iri, value).rejected shouldEqual
        StorageNotAccessible(iri, s"Volume '$volume' is not a directory.")
    }

    "fail when volume does not have write access" in {
      val volume = AbsolutePath(Files.createTempDirectory("disk-not-access")).rightValue
      volume.value.toFile.setReadOnly()
      val value  = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, Some(100), 10)
      DiskStorageAccess(iri, value).rejected shouldEqual
        StorageNotAccessible(iri, s"Volume '$volume' does not have write access.")
    }
  }

}
