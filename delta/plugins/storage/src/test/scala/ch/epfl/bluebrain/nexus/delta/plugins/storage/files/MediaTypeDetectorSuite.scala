package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class MediaTypeDetectorSuite extends NexusSuite {

  private val customMediaType   = MediaType("application", "custom")
  private val mediaTypeDetector = new MediaTypeDetector(MediaTypeDetectorConfig(Map("custom" -> customMediaType)))

  private val json        = Some(MediaType.`application/json`)
  private val octetStream = Some(MediaType.`application/octet-stream`)

  test("Return the default media type for an unknown extension and no input") {
    assertEquals(
      mediaTypeDetector("file.unknown", None, octetStream),
      octetStream
    )
  }

  test("Return the http4s known extension and no input") {
    assertEquals(
      mediaTypeDetector("file.json", None, octetStream),
      json
    )
  }

  test("Return the matching media type for an defined extension and no input") {
    assertEquals(
      mediaTypeDetector("file.custom", None, octetStream),
      Some(customMediaType)
    )
  }

  test("Return the provided input as a priority") {
    assertEquals(
      mediaTypeDetector("file.custom", json, octetStream),
      json
    )
  }
}
