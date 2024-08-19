package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.{ContentTypes, MediaTypes}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class FileProcessorSuite extends NexusSuite {

  implicit private val mediaTypeDetector: MediaTypeDetectorConfig = MediaTypeDetectorConfig(
    "json" -> MediaTypes.`application/json`,
    "pdf"  -> MediaTypes.`application/pdf`
  )

  test("Return a new content type matching the config") {
    assertEquals(
      FileProcessor.patchMediaType("file.json", None),
      Some(ContentTypes.`application/json`)
    )
  }

  test("Return the original content type") {
    assertEquals(
      FileProcessor.patchMediaType("file.", Some(ContentTypes.`text/csv(UTF-8)`)),
      Some(ContentTypes.`text/csv(UTF-8)`)
    )
  }

  test("Patching media type for a media type that changes") {
    assertEquals(
      FileProcessor.forceMediaType(
        Some(ContentTypes.`application/octet-stream`),
        Some(ContentTypes.`application/json`)
      ),
      true
    )
  }

  test("Patching media type for a media type that does not change") {
    assertEquals(
      FileProcessor.forceMediaType(
        Some(ContentTypes.`application/json`),
        Some(ContentTypes.`application/json`)
      ),
      false
    )
  }

}
