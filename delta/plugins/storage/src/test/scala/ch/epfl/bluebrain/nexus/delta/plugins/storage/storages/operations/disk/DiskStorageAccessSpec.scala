package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.nio.file.{Files, Path}

class DiskStorageAccessSpec extends CatsEffectSpec {

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
