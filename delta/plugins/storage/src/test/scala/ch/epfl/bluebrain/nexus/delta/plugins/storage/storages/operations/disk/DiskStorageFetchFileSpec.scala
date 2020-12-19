package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Files

class DiskStorageFetchFileSpec
    extends TestKit(ActorSystem("DiskStorageFetchFileSpec"))
    with AkkaSourceHelpers
    with AnyWordSpecLike
    with Matchers
    with IOValues {

  "A DiskStorage fetching operations" should {

    "fetch a file from a volume" in {
      val volume = Files.createTempDirectory("disk-access")
      val file   = volume.resolve("my/file.txt")
      Files.createDirectories(file.getParent)
      Files.createFile(file)
      Files.writeString(file, "file content")

      val source = DiskStorageFetchFile(Uri.Path(file.toString)).accepted
      consume(source) shouldEqual "file content"
      Files.delete(file)
    }
  }

}
