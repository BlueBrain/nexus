package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef._
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.Json
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.syntax._

class ResourceRefSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable {

  "A ResourceRef" should {

    // format: off
    val list = List(
      iri"http://ex.com?rev=1&other=value"          -> Revision(iri"http://ex.com?rev=1&other=value", iri"http://ex.com?other=value", 1L),
      iri"http://ex.com?rev=1"                      -> Revision(iri"http://ex.com?rev=1", iri"http://ex.com", 1L),
      iri"http://ex.com?tag=this&other=value"       -> Tag(iri"http://ex.com?tag=this&other=value", iri"http://ex.com?other=value", TagLabel.unsafe("this")),
      iri"http://ex.com?rev=1&tag=this&other=value" -> Revision(iri"http://ex.com?rev=1&tag=this&other=value" , iri"http://ex.com?other=value", 1L),
      iri"http://ex.com?other=value"                -> Latest(iri"http://ex.com?other=value"),
      iri"http://ex.com#fragment"                   -> Latest(iri"http://ex.com#fragment")
    )
    // format: on

    "be constructed from an Iri" in {
      forAll(list) { case (iri, resourceRef) =>
        ResourceRef(iri) shouldEqual resourceRef
      }
    }

    "be constructed from json" in {
      forAll(list) { case (iri, resourceRef) =>
        Json.fromString(iri.toString).as[ResourceRef].rightValue shouldEqual resourceRef
      }
    }

    "be converted to json" in {
      forAll(list) { case (iri, resourceRef) =>
        resourceRef.asJson shouldEqual Json.fromString(iri.toString)
      }
    }
  }

}
