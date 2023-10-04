package ch.epfl.bluebrain.nexus.storage.attributes

import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.{ContentType, ContentTypes, MediaTypes}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import munit.FunSuite

import java.nio.file.Paths

class ContentTypeDetectorSuite extends FunSuite {

  private val jsonPath        = Paths.get("content-type/file-example.json")
  private val noExtensionPath = Paths.get("content-type/no-extension")

  test("Detect 'application/json' as content type") {
    val detector = new ContentTypeDetector(MediaTypeDetectorConfig.Empty)
    val expected = ContentTypes.`application/json`
    assertEquals(detector(jsonPath, isDir = false), expected)
  }

  test("Detect overridden content type") {
    val customMediaType = MediaTypes.`application/vnd.api+json`
    val detector        = new ContentTypeDetector(MediaTypeDetectorConfig("json" -> MediaTypes.`application/vnd.api+json`))
    val expected        = ContentType(customMediaType, () => `UTF-8`)
    assertEquals(detector(jsonPath, isDir = false), expected)
  }

  test("Detect `application/octet-stream` as a default value") {
    val detector = new ContentTypeDetector(MediaTypeDetectorConfig("json" -> MediaTypes.`application/vnd.api+json`))
    val expected = ContentTypes.`application/octet-stream`
    assertEquals(detector(noExtensionPath, isDir = false), expected)
  }

  test("Detect `application/x-tar` when the flag directory is set") {
    val detector = new ContentTypeDetector(MediaTypeDetectorConfig("json" -> MediaTypes.`application/vnd.api+json`))
    val expected = ContentType(MediaTypes.`application/x-tar`, () => `UTF-8`)
    assertEquals(detector(noExtensionPath, isDir = true), expected)
  }
}
