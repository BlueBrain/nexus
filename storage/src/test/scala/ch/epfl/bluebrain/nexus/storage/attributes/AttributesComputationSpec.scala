package ch.epfl.bluebrain.nexus.storage.attributes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContextExecutor

class AttributesComputationSpec extends TestKit(ActorSystem("AttributesComputationSpec")) with CatsEffectSpec {

  implicit private val ec: ExecutionContextExecutor     = system.dispatcher
  implicit val contentTypeDetector: ContentTypeDetector = new ContentTypeDetector(MediaTypeDetectorConfig.Empty)

  private trait Ctx {
    val path           = Files.createTempFile("storage-test", ".txt")
    val (text, digest) = "something" -> "3fc9b689459d738f8c88a3a48aa9e33542016b7a4052e001aaa536fca74813cb"
  }

  "Attributes computation computation" should {
    val computation = AttributesComputation.akkaAttributes
    val alg         = "SHA-256"

    "succeed" in new Ctx {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      computation(path, alg).accepted shouldEqual FileAttributes(
        s"file://$path",
        Files.size(path),
        Digest(alg, digest),
        `text/plain(UTF-8)`
      )
      Files.deleteIfExists(path)
    }

    "fail when algorithm is wrong" in new Ctx {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      computation(path, "wrong-alg").rejectedWith[InternalError]
    }

    "fail when file does not exists" in new Ctx {
      computation(Paths.get("/tmp/non/existing"), alg).rejectedWith[InternalError]
    }
  }
}
