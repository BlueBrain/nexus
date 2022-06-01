package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.EitherAssertions
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json
import io.circe.syntax._
import munit.FunSuite

class ResourceRefSuite extends FunSuite with EitherAssertions {

  // format: off
  val list = List(
    iri"http://ex.com?rev=1&other=value"          -> Revision(iri"http://ex.com?rev=1&other=value", iri"http://ex.com?other=value", 1L),
    iri"http://ex.com?rev=1"                      -> Revision(iri"http://ex.com?rev=1", iri"http://ex.com", 1L),
    iri"http://ex.com?tag=this&other=value"       -> Tag(iri"http://ex.com?tag=this&other=value", iri"http://ex.com?other=value", UserTag.unsafe("this")),
    iri"http://ex.com?rev=1&tag=this&other=value" -> Revision(iri"http://ex.com?rev=1&tag=this&other=value" , iri"http://ex.com?other=value", 1L),
    iri"http://ex.com?other=value"                -> Latest(iri"http://ex.com?other=value"),
    iri"http://ex.com#fragment"                   -> Latest(iri"http://ex.com#fragment")
  )
  // format: on

  list.foreach { case (iri, resourceRef) =>
    test(s"$iri should be properly constructed") {
      assertEquals(ResourceRef(iri), resourceRef)
    }

    test(s"$iri should be constructed from json") {
      Json.fromString(iri.toString).as[ResourceRef].assertRight(resourceRef)
    }

    test(s"$resourceRef should be converted to json") {
      assertEquals(resourceRef.asJson, Json.fromString(iri.toString))
    }
  }

}
