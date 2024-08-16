package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.{ContentTypes, MediaTypes}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

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
        Instant.now,
        Some(ContentTypes.`application/json`)
      ),
      true
    )
  }

  test("Patching media type for a png file older than the given instant") {
    val imagePng = MediaTypes.`image/png`.toContentType
    assertEquals(
      FileProcessor.forceMediaType(Some(imagePng), Instant.parse("2021-04-11T19:19:16.578Z"), Some(imagePng)),
      true
    )
  }

  test("Not patching media type for a png file after the given instant") {
    val imagePng = MediaTypes.`image/png`.toContentType
    assertEquals(
      FileProcessor.forceMediaType(Some(imagePng), Instant.parse("2021-04-11T19:19:16.580Z"), Some(imagePng)),
      false
    )
  }

  test("Patching media type for a tiff file older than the given instant") {
    val imageTiff = MediaTypes.`image/tiff`.toContentType
    assertEquals(
      FileProcessor.forceMediaType(Some(imageTiff), Instant.parse("2021-04-11T19:19:16.578Z"), Some(imageTiff)),
      true
    )
  }

  test("Not patching media type for a fiff file after the given instant") {
    val imageTiff = MediaTypes.`image/tiff`.toContentType
    assertEquals(
      FileProcessor.forceMediaType(Some(imageTiff), Instant.parse("2021-04-11T19:19:16.580Z"), Some(imageTiff)),
      false
    )
  }

}
