package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.{
  RemoteContextCircularDependency,
  RemoteContextNotFound
}
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RemoteContextResolutionSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A remote context resolution" should {

    val input = jsonContentOf("/jsonld/context/input-with-remote-context.json")

    "resolve" in {
      val expected = remoteContexts.map { case (k, v) => k -> v.topContextValueOrEmpty }
      remoteResolution(input).runSyncUnsafe() shouldEqual expected
    }

    "fail to resolve when there are circular dependencies" in {
      // format: off
      val contexts: Map[Uri, Json] =
        Map(
          Uri("http://example.com/context/0") -> json"""{"@context": {"deprecated": {"@id": "http://schema.org/deprecated", "@type": "http://www.w3.org/2001/XMLSchema#boolean"} }}""",
          Uri("http://example.com/context/1") -> json"""{"@context": ["http://example.com/context/11", "http://example.com/context/12"] }""",
          Uri("http://example.com/context/11") -> json"""{"@context": [{"birthDate": "http://schema.org/birthDate"}, "http://example.com/context/1"] }""",
          Uri("http://example.com/context/12") -> json"""{"@context": {"Other": "http://schema.org/Other"} }""",
          Uri("http://example.com/context/2") -> json"""{"@context": {"integerAlias": "http://www.w3.org/2001/XMLSchema#integer", "type": "@type"} }""",
          Uri("http://example.com/context/3") -> json"""{"@context": {"customid": {"@type": "@id"} } }"""
        )
      // format: on

      val remoteResolution = resolution(contexts)
      remoteResolution(input).attempt.runSyncUnsafe().leftValue shouldEqual
        RemoteContextCircularDependency("http://example.com/context/1")
    }

    "fail to resolve when some context does not exist" in {
      val excluded                 = "http://example.com/context/3"
      val contexts: Map[Uri, Json] = remoteContexts - excluded
      val remoteResolution         = resolution(contexts)
      remoteResolution(input).attempt.runSyncUnsafe().leftValue shouldEqual RemoteContextNotFound(excluded)
    }
  }

}
