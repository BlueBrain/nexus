package ch.epfl.bluebrain.nexus.migration.v1_4

import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SourceSanitizerSpec extends AnyWordSpecLike with Matchers with CirceLiteral {

  "Replacing old contexts" should {

    val id      = iri"https://bbp.neuroshapes.org"
    val otherId = iri"https://neuroshapes.org"

    "work in an array" in {
      val original =
        json"""{"@context" : ["https://neuroshapes.org","https://bluebrain.github.io/nexus/contexts/resource.json",{"@vocab":"https://bbp.epfl.ch/nexus/v1/resources/bbp/neocortex/_/","@base":"https://bbp.epfl.ch/neurosciencegraph/data/"}]}"""
      SourceSanitizer.sanitize(otherId)(
        original
      ) shouldEqual json"""{"@context" : ["https://neuroshapes.org",{"@vocab":"https://bbp.epfl.ch/nexus/v1/resources/bbp/neocortex/_/","@base":"https://bbp.epfl.ch/neurosciencegraph/data/"}]}"""
    }

    "work with a single value" in {
      val original = json"""{"@context" : "https://bluebrain.github.io/nexus/contexts/resolver.json"}"""
      SourceSanitizer.sanitize(otherId)(
        original
      ) shouldEqual json"""{"@context" : "https://bluebrain.github.io/nexus/contexts/resolvers.json"}"""
    }

    "remove context and metadata field" in {
      val original =
        json"""{"@context" : "https://bluebrain.github.io/nexus/contexts/resource.json", "_createdAt": "Removed", "other": "Remains"}"""
      SourceSanitizer.sanitize(otherId)(
        original
      ) shouldEqual json"""{"other": "Remains"}"""
    }

    "remove base and vocab for the given id" in {
      val original =
        json"""{"@context":["https://neuroshapes.org",{"@vocab":"https://bbp.epfl.ch/nexus/v1/resources/bbp/neocortex/_/","@base":"https://bbp.epfl.ch/neurosciencegraph/data/"}]}"""
      SourceSanitizer.sanitize(id)(
        original
      ) shouldEqual json"""{"@context":["https://neuroshapes.org"]}"""
    }

  }

}
