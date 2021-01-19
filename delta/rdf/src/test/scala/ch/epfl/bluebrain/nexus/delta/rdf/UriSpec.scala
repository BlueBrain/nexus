package ch.epfl.bluebrain.nexus.delta.rdf

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.Json
import io.circe.syntax._
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class UriSpec extends AnyWordSpecLike with Matchers with EitherValuable with Inspectors with OptionValues {
  "A Uri" should {
    val uriString = "http://example.com/path"
    val uri       = uri"$uriString"

    "be constructed from string" in {
      uriString.toUri.rightValue shouldEqual uri
    }

    "be constructed from Iri" in {
      val iri = iri"$uriString"
      iri.toUri.rightValue shouldEqual uri
    }

    "fail to construct from Iri" in {
      val iri = iri"http://example.com/é"
      iri.toUri.leftValue
    }

    "failed to be constructed from string" in {
      "http://éxample.com".toUri.leftValue
    }

    "append segment" in {
      val list     = List(
        uri"http://example.com/a"   -> "b",
        uri"http://example.com/a/"  -> "/b",
        uri"http://example.com/a/"  -> "b",
        uri"http://example.com/a"   -> "/b",
        uri"http://example.com/a/b" -> ""
      )
      val expected = uri"http://example.com/a/b"
      forAll(list) { case (uri, segment) => (uri / segment) shouldEqual expected }
    }

    "append path" in {
      val list     = List(
        uri"http://example.com/a"     -> Uri.Path("b/c"),
        uri"http://example.com/a/"    -> Uri.Path("/b/c"),
        uri"http://example.com/a/"    -> Uri.Path("b/c"),
        uri"http://example.com/a"     -> Uri.Path("/b/c"),
        uri"http://example.com/a/b/c" -> Uri.Path("")
      )
      val expected = uri"http://example.com/a/b/c"
      forAll(list) { case (uri, path) => (uri / path) shouldEqual expected }
    }

    "be converted to Json" in {
      uri.asJson shouldEqual Json.fromString(uriString)
    }

    "be constructed from Json" in {
      Json.fromString(uriString).as[Uri].rightValue shouldEqual uri
    }
  }

  "A Path" should {
    val path       = Uri.Path("my/path")
    val pathString = "my/path"

    "extract its last segment" in {
      forAll(List(Uri.Path("/file.txt"), Uri.Path("/some/other/file.txt"), Uri.Path("file.txt"))) { path =>
        path.lastSegment.value shouldEqual "file.txt"
      }

      forAll(List(Uri.Path("/file/"), Uri.Path("/"), Uri.Path(""))) { path =>
        path.lastSegment shouldEqual None
      }
    }

    "be converted to Json" in {
      path.asJson shouldEqual Json.fromString(pathString)
    }

    "be constructed from Json" in {
      Json.fromString(pathString).as[Uri.Path].rightValue shouldEqual path
    }
  }

}
