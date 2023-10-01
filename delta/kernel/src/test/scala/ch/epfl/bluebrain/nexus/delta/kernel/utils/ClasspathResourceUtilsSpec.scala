package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, InvalidJsonObject, ResourcePathNotFound}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import ClasspathResourceUtils._

class ClasspathResourceUtilsSpec extends AnyWordSpecLike with Matchers with ScalaFutures {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  private def accept[A](io: IO[A]): A =
    io.attempt.unsafeRunSync() match {
      case Left(value)  => fail(s"Expected Right, found Left with value '$value'")
      case Right(value) => value
    }

  private def reject[A](io: IO[A]): Throwable =
    io.attempt.unsafeRunSync() match {
      case Left(value)  => value
      case Right(value) => fail(s"Expected Left, found Right with value '$value'")
    }

  "A ClasspathResourceUtils" should {
    val resourceIO = ioContentOf("resource.txt", "value" -> "v")

    "return the path" in {
      accept(absolutePath("resource.txt")) should endWith("resource.txt")
    }

    "return a text" in {
      accept(resourceIO) shouldEqual "A text resource with replacement 'v'"
    }

    "read a second time" in {
      accept(resourceIO) shouldEqual "A text resource with replacement 'v'"
    }

    "return a json" in {
      accept(ioJsonContentOf("resource.json", "value" -> "v")) shouldEqual Json.obj("k" -> "v".asJson)
    }

    "return a json object" in {
      accept(ioJsonObjectContentOf("resource.json", "value" -> "v")) shouldEqual JsonObject("k" -> "v".asJson)
    }

    "fail when resource is not a json" in {
      reject(ioJsonContentOf("resource.txt")) shouldBe a[InvalidJson]
    }

    "fail when resource is not a json object" in {
      reject(ioJsonObjectContentOf("resource-json-array.json")) shouldEqual InvalidJsonObject(
        "resource-json-array.json"
      )
    }

    "fail when resource does not exists" in {
      reject(ioContentOf("resource2.txt", "value" -> "v")) shouldEqual ResourcePathNotFound(
        "resource2.txt"
      )
    }
  }
}
