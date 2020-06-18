package ch.epfl.bluebrain.nexus.storage.attributes

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import ch.epfl.bluebrain.nexus.storage.utils.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContextExecutor

class AttributesComputationSpec
    extends TestKit(ActorSystem("AttributesComputationSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues {

  implicit private val ec: ExecutionContextExecutor = system.dispatcher

  private trait Ctx {
    val path           = Files.createTempFile("storage-test", ".txt")
    val (text, digest) = "something" -> "3fc9b689459d738f8c88a3a48aa9e33542016b7a4052e001aaa536fca74813cb"
  }

  "Attributes computation computation" should {
    val computation = AttributesComputation.akkaAttributes[IO]
    val alg         = "SHA-256"

    "succeed" in new Ctx {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      computation(path, alg).ioValue shouldEqual FileAttributes(
        s"file://$path",
        Files.size(path),
        Digest(alg, digest),
        `text/plain(UTF-8)`
      )
      Files.deleteIfExists(path)
    }

    "fail when algorithm is wrong" in new Ctx {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      computation(path, "wrong-alg").failed[InternalError]
    }

    "fail when file does not exists" in new Ctx {
      computation(Paths.get("/tmp/non/existing"), alg).failed[InternalError]
    }
  }
}
