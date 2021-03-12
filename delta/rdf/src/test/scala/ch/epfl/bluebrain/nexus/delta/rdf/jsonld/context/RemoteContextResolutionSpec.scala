package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotFound
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RemoteContextResolutionSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A remote context resolution" should {

    val input = jsonContentOf("/jsonld/context/input-with-remote-context.json")

    "resolve" in {
      remoteResolution(input).accepted shouldEqual remoteContexts
    }

    "fail to resolve when some context does not exist" in {
      val excluded         = iri"http://example.com/cöntéxt/3"
      val ctxValuesMap     = remoteContexts - excluded
      val remoteResolution = RemoteContextResolution.fixed(ctxValuesMap.toSeq: _*)
      remoteResolution(input).rejected shouldEqual RemoteContextNotFound(excluded)
    }

    "merge and resolve" in {
      val excluded           = iri"http://example.com/cöntéxt/3"
      val ctxValue           = remoteContexts(excluded)
      val ctxValuesMap       = remoteContexts - excluded
      val excludedResolution = RemoteContextResolution.fixed(excluded -> ctxValue)
      val restResolution     = RemoteContextResolution.fixed(ctxValuesMap.toSeq: _*)
      restResolution.merge(excludedResolution).resolve(excluded).accepted shouldEqual ctxValue
    }
  }

}
