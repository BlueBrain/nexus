package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.Location

class FileResponseSuite extends NexusSuite {

  private def assertContentType(value: String, binary: Boolean, compressible: Boolean)(implicit l: Location) =
    ContentType.parse(value) match {
      case Left(err)          => fail(s"Could not parse the content type $err")
      case Right(contentType) =>
        val marked = FileResponse.markBinaryAsNonCompressible(contentType)
        assertEquals(marked.binary, binary, "Binary attribute is wrong")
        assertEquals(marked.mediaType.comp.compressible, compressible, "Compressible attribute is wrong")
    }

  test("'application/object-stream' is binary and not compressible") {
    assertContentType("application/octet-stream", true, false)
  }

  test("'application/json' is not binary and compressible") {
    assertContentType("application/json", false, true)
  }

  test("'application/cbor' is binary and compressible as it is registered as such in akka") {
    assertContentType("application/cbor", true, true)
  }

  test("'application/nrrd' is binary and not compressible as it is a custom type") {
    assertContentType("application/nrrd", true, false)
  }

}
