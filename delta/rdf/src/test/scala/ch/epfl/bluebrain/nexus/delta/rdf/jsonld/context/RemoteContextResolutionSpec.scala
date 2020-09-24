package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.{Fixtures, RemoteContextResolutionDummy}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.{RemoteContextCircularDependency, RemoteContextNotFound}
import io.circe.Json
import org.apache.jena.iri.IRI
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RemoteContextResolutionSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A remote context resolution" should {

    val input = jsonContentOf("/jsonld/context/input-with-remote-context.json")

    "resolve" in {
      val expected = remoteContexts.map { case (k, v) => k -> v.topContextValueOrEmpty }
      remoteResolution(input).accepted shouldEqual expected
    }

    "fail to resolve when there are circular dependencies" in {
      // format: off
      val contexts: Map[IRI, Json] =
        Map(
          iri"http://example.com/cöntéxt/0" -> json"""{"@context": {"deprecated": {"@id": "http://schema.org/deprecated", "@type": "http://www.w3.org/2001/XMLSchema#boolean"} }}""",
          iri"http://example.com/cöntéxt/1" -> json"""{"@context": ["http://example.com/cöntéxt/11", "http://example.com/cöntéxt/12"] }""",
          iri"http://example.com/cöntéxt/11" -> json"""{"@context": [{"birthDate": "http://schema.org/birthDate"}, "http://example.com/cöntéxt/1"] }""",
          iri"http://example.com/cöntéxt/12" -> json"""{"@context": {"Other": "http://schema.org/Other"} }""",
          iri"http://example.com/cöntéxt/2" -> json"""{"@context": {"integerAlias": "http://www.w3.org/2001/XMLSchema#integer", "type": "@type"} }""",
          iri"http://example.com/cöntéxt/3" -> json"""{"@context": {"customid": {"@type": "@id"} } }"""
        )
      // format: on

      val remoteResolution = new RemoteContextResolutionDummy(contexts)
      remoteResolution(input).rejected shouldEqual RemoteContextCircularDependency(iri"http://example.com/cöntéxt/1")
    }

    "fail to resolve when some context does not exist" in {
      val excluded                 = iri"http://example.com/cöntéxt/3"
      val contexts: Map[IRI, Json] = remoteContexts - excluded
      val remoteResolution         = new RemoteContextResolutionDummy(contexts)
      remoteResolution(input).rejected shouldEqual RemoteContextNotFound(excluded)
    }
  }

}
