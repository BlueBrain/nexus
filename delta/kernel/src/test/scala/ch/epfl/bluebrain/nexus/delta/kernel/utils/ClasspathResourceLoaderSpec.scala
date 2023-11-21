package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.IO
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, InvalidJsonObject}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ClasspathResourceLoaderSpec extends AnyWordSpecLike with Matchers with ScalaFutures {
  private val loader: ClasspathResourceLoader = ClasspathResourceLoader()

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
    val resourceIO = loader.contentOf("resource.txt", "value" -> "v")

    "return the path" in {
      accept(loader.absolutePath("resource.txt")) should endWith("resource.txt")
    }

    "return a text" in {
      accept(resourceIO) shouldEqual "A text resource with replacement 'v'"
    }

    "read a second time" in {
      accept(resourceIO) shouldEqual "A text resource with replacement 'v'"
    }

    "return a json" in {
      accept(loader.jsonContentOf("resource.json", "value" -> "v")) shouldEqual Json.obj("k" -> "v".asJson)
    }

    "return a json object" in {
      accept(loader.jsonObjectContentOf("resource.json", "value" -> "v")) shouldEqual JsonObject("k" -> "v".asJson)
    }

    "fail when resource is not a json" in {
      reject(loader.jsonContentOf("resource.txt")) shouldBe a[InvalidJson]
    }

    "fail when resource is not a json object" in {
      reject(loader.jsonObjectContentOf("resource-json-array.json")) shouldEqual InvalidJsonObject(
        "resource-json-array.json"
      )
    }

    "fail when resource does not exists" in {
      reject(loader.contentOf("resource2.txt", "value" -> "v")).getMessage should (include("not found") and include(
        "resource2.txt"
      ))
    }
  }
}
