package ch.epfl.bluebrain.nexus.kg.storage

import java.nio.file.Paths
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File.Digest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class PackageObjectSpec extends AnyFlatSpecLike with Matchers with ScalaFutures {

  "uriToPath" should "convert an Akka Uri that represents a valid file path to a Java Path" in {
    uriToPath("file:///some/path/my%20file.txt") shouldEqual Some(Paths.get("/some/path/my file.txt"))
    uriToPath("s3://some/path") shouldEqual None
    uriToPath("foo") shouldEqual None
  }

  "pathToUri" should "convert a Java Path to an Akka Uri" in {
    pathToUri(Paths.get("/some/path/my file.txt")) shouldEqual Uri("file:///some/path/my%20file.txt")
  }

  "mangle" should "generate a properly mangled path given a file project and UUID" in {
    val projUuid = UUID.fromString("4947db1e-33d8-462b-9754-3e8ae74fcd4e")
    val fileUuid = UUID.fromString("b1d7cda2-1ec0-40d2-b12e-3baf4895f7d7")
    mangle(ProjectRef(projUuid), fileUuid, "my file.jpg") shouldEqual
      "4947db1e-33d8-462b-9754-3e8ae74fcd4e/b/1/d/7/c/d/a/2/my file.jpg"
  }

  "digest" should "properly compute the hash of a given input" in {
    implicit val as: ActorSystem = ActorSystem()

    val filePath = "/storage/s3.json"
    val path     = Paths.get(getClass.getResource(filePath).toURI)
    val input    = FileIO.fromPath(path)
    val algo     = "SHA-256"

    input.runWith(digestSink(algo)(as.dispatcher)).futureValue shouldEqual Digest(
      algo,
      "5602c497e51680bef1f3120b1d6f65d480555002a3290029f8178932e8f4801a"
    )
  }
}
