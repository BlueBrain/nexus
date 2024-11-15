package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.{ContentTypes, MediaTypes, Uri}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.localDiskPath
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class FileProcessorSuite extends NexusSuite {

  implicit private val mediaTypeDetector: MediaTypeDetectorConfig = MediaTypeDetectorConfig(
    "json" -> MediaTypes.`application/json`,
    "pdf"  -> MediaTypes.`application/pdf`
  )

  test("Correctly decode a local path") {
    val encoded  = Uri.Path("org/proj/9/f/0/3/2/4/f/e/0925_Rhi13.3.13%20cell%201+2%20(superficial).asc")
    val obtained = localDiskPath(encoded)
    val expected = "/org/proj/9/f/0/3/2/4/f/e/0925_Rhi13.3.13 cell 1+2 (superficial).asc"
    assertEquals(obtained, expected)
  }

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

}
