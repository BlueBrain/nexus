package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.mu.EitherAssertions
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class ResourceRefSuite extends FunSuite with EitherAssertions {

  // format: off
  private val list = List(
    iri"https://bbp.epfl.ch?rev=1&other=value"          -> Revision(iri"https://bbp.epfl.ch?rev=1&other=value", iri"https://bbp.epfl.ch?other=value", 1),
    iri"https://bbp.epfl.ch?rev=1"                      -> Revision(iri"https://bbp.epfl.ch?rev=1", iri"https://bbp.epfl.ch", 1),
    iri"https://bbp.epfl.ch?tag=this&other=value"       -> Tag(iri"https://bbp.epfl.ch?tag=this&other=value", iri"https://bbp.epfl.ch?other=value", UserTag.unsafe("this")),
    iri"https://bbp.epfl.ch?rev=1&tag=this&other=value" -> Revision(iri"https://bbp.epfl.ch?rev=1&tag=this&other=value" , iri"https://bbp.epfl.ch?other=value", 1),
    iri"https://bbp.epfl.ch?other=value"                -> Latest(iri"https://bbp.epfl.ch?other=value"),
    iri"https://bbp.epfl.ch#fragment"                   -> Latest(iri"https://bbp.epfl.ch#fragment")
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
