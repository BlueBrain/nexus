package ch.epfl.bluebrain.nexus.delta.kernel.instances

import io.circe.Json
import munit.{FunSuite, Location}

class ContentTypeInstancesSuite extends FunSuite with ContentTypeInstances {

  def assertContentType(value: String, binary: Boolean, compressible: Boolean)(implicit l: Location) =
    contentTypeDecoder.decodeJson(Json.fromString(value)) match {
      case Left(err)          => fail(s"Could not decode $value: ${err.message}")
      case Right(contentType) =>
        assertEquals(contentType.binary, binary)
        assertEquals(contentType.mediaType.comp.compressible, compressible)
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
