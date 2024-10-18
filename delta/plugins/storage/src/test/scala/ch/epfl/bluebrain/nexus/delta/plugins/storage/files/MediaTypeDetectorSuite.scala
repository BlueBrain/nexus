package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, MediaType}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class MediaTypeDetectorSuite extends NexusSuite {

  private val customMediaType   = MediaType.custom("application/custom", binary = false)
  private val customContentType = ContentType(customMediaType, () => HttpCharsets.`UTF-8`)
  private val mediaTypeDetector = new MediaTypeDetector(MediaTypeDetectorConfig(Map("custom" -> customMediaType)))

  private val json        = Some(ContentTypes.`application/json`)
  private val octetStream = Some(ContentTypes.`application/octet-stream`)

  test("Return the default content type for an unknown extension and no input") {
    assertEquals(
      mediaTypeDetector("file.obj", None, octetStream),
      octetStream
    )
  }

  test("Return the akka known extension and no input") {
    assertEquals(
      mediaTypeDetector("file.json", None, octetStream),
      json
    )
  }

  test("Return the matching content type for an defined extension and no input") {
    assertEquals(
      mediaTypeDetector("file.custom", None, octetStream),
      Some(customContentType)
    )
  }

  test("Return the provided input as a priority") {
    assertEquals(
      mediaTypeDetector("file.custom", json, octetStream),
      json
    )
  }
}
