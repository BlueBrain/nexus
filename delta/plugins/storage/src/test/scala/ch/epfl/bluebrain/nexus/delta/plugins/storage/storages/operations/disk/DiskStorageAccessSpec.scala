package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.UUIDFFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.nio.file.{Files, Path}

class DiskStorageAccessSpec
    extends TestKit(ActorSystem("DiskStorageAccessSpec"))
    with CatsEffectSpec
    with UUIDFFixtures.Random {

  private val fileOps = DiskFileOperations.mk

  "A DiskStorage access operations" should {

    "succeed verifying the volume" in {
      val volume = AbsolutePath(Files.createTempDirectory("disk-access")).rightValue
      fileOps.checkVolumeExists(volume).accepted
    }

    "fail when volume does not exist" in {
      val volume = AbsolutePath(Path.of("/random", genString())).rightValue
      fileOps.checkVolumeExists(volume).rejected shouldEqual StorageNotAccessible(s"Volume '$volume' does not exist.")
    }

    "fail when volume is not a directory" in {
      val volume = AbsolutePath(Files.createTempFile(genString(), genString())).rightValue
      fileOps.checkVolumeExists(volume).rejected shouldEqual StorageNotAccessible(
        s"Volume '$volume' is not a directory."
      )
    }

    "fail when volume does not have write access" in {
      val volume = AbsolutePath(Files.createTempDirectory("disk-not-access")).rightValue
      volume.value.toFile.setReadOnly()
      fileOps.checkVolumeExists(volume).rejected shouldEqual StorageNotAccessible(
        s"Volume '$volume' does not have write access."
      )
    }
  }

}
