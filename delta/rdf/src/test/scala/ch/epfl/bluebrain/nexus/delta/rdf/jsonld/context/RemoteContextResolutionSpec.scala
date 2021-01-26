package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotFound
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RemoteContextResolutionSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A remote context resolution" should {

    val input = jsonContentOf("/jsonld/context/input-with-remote-context.json")

    "resolve" in {
      val expected = remoteContexts.map { case (k, v) => k -> v.topContextValueOrEmpty }
      remoteResolution(input).accepted shouldEqual expected
    }

    "fail to resolve when some context does not exist" in {
      val excluded                 = iri"http://example.com/cöntéxt/3"
      val contexts: Map[Iri, Json] = remoteContexts - excluded
      val remoteResolution         = RemoteContextResolution.fixed(contexts.toSeq: _*)
      remoteResolution(input).rejected shouldEqual RemoteContextNotFound(excluded)
    }

    "merge and resolve" in {
      val excluded                 = iri"http://example.com/cöntéxt/3"
      val json                     = remoteContexts(excluded)
      val contexts: Map[Iri, Json] = remoteContexts - excluded
      val excludedResolution       = RemoteContextResolution.fixed(excluded -> json)
      val restResolution           = RemoteContextResolution.fixed(contexts.toSeq: _*)
      restResolution.merge(excludedResolution).resolve(excluded).accepted shouldEqual json
    }
  }

}
