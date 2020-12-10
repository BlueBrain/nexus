package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.Json
import org.scalatest.Inspectors
import io.circe.syntax._

class UriSpec extends AnyWordSpecLike with Matchers with EitherValuable with Inspectors {
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

    "be converted to Json" in {
      uri.asJson shouldEqual Json.fromString(uriString)
    }

    "be constructed from Json" in {
      Json.fromString(uriString).as[Uri].rightValue shouldEqual uri
    }
  }

}
