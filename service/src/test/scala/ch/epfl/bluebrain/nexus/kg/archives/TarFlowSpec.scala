package ch.epfl.bluebrain.nexus.kg.archives

import java.nio.file.Files
import java.time.{Clock, Instant, ZoneId}

import akka.actor.ActorSystem
import akka.stream.scaladsl.FileIO
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.storage.digestSink
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class TarFlowSpec
    extends TestKit(ActorSystem("TarFlowSpec"))
    with AnyWordSpecLike
    with Matchers
    with TestHelper
    with ScalaFutures {

  implicit private val ec    = system.dispatcher
  implicit private val clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(55.second, 150.milliseconds)

  "A TarFlow" should {

    "tar a bunch of sources" in {
      val digest   =
        "3fef41c5afe7a7ee11ee9d556a564fb57784cc5247b24c6ca70783f396fa158a1c7952504d3e1aa441de20cf065d740eec454c6ffb7fbc4b6351b950ee51c886"
      val elems    = 500
      val contents =
        List.tabulate(2) { i =>
          val content = (i until (i + elems)).toList.mkString(",") + "\n"
          ArchiveSource(content.length.toLong, s"some/path$i/$i.txt", produce(content))
        }
      val path     = Files.createTempFile("test", ".tar")
      TarFlow.write(contents).runWith(FileIO.toPath(path)).futureValue
      FileIO.fromPath(path).runWith(digestSink("SHA-512")).futureValue.value shouldEqual digest
      Files.delete(path)
    }
  }
}
