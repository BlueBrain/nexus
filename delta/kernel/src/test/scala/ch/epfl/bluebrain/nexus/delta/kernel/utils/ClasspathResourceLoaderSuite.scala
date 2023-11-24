package ch.epfl.bluebrain.nexus.delta.kernel.utils

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, InvalidJsonObject}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite

class ClasspathResourceLoaderSuite extends CatsEffectSuite {
  private val loader: ClasspathResourceLoader = ClasspathResourceLoader()

  test("return the path") {
    loader.absolutePath("resource.txt").assert(_.endsWith("resource.txt"))
  }

  test("return the contents of a handlebar template") {
    loader
      .contentOf("resource.txt", "value" -> "v")
      .assertEquals("A text resource with replacement 'v'")
  }

  test("return the contents of a handlebar template, multiple times") {
    val io = loader.contentOf("resource.txt", "value" -> "v")

    for {
      _ <- io.assertEquals("A text resource with replacement 'v'")
      _ <- io.assertEquals("A text resource with replacement 'v'")
    } yield {
      ()
    }
  }

  test("return the contents of a handlebar template as json") {
    loader
      .jsonContentOf("resource.json", "value" -> "v")
      .assertEquals(Json.obj("k" -> "v".asJson))
  }

  test("fail when a file cannot be parsed as json") {
    loader
      .jsonContentOf("resource.txt", "value" -> "v")
      .intercept[InvalidJson]
  }

  test("return the contaents of a handlebar template as a json object") {
    loader
      .jsonObjectContentOf("resource.json", "value" -> "v")
      .assertEquals(JsonObject("k" -> "v".asJson))
  }

  test("fail when a file contains JSON but is not a json object") {
    loader
      .jsonObjectContentOf("resource-json-array.json")
      .intercept[InvalidJsonObject]
      .assertEquals(
        InvalidJsonObject("resource-json-array.json")
      )
  }

  test("fail when a resource does not exist") {
    loader
      .contentOf("resource2.txt", "value" -> "v")
      .intercept[Throwable]
      .assert(e => e.getMessage.contains("not found") && e.getMessage.contains("resource2.txt"))
  }
}
