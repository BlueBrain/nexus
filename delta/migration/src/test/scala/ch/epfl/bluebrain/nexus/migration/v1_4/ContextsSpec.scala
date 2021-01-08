package ch.epfl.bluebrain.nexus.migration.v1_4

import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ContextsSpec extends AnyWordSpecLike with Matchers with CirceLiteral {

  "Replacing old contexts" should {

    "work in an array" in {
      val original =
        json"""{"@context" : ["https://bbp.neuroshapes.org","https://bluebrain.github.io/nexus/contexts/resource.json"]}"""
      Contexts.updateContext(
        original
      ) shouldEqual (json"""{"@context" : ["https://bbp.neuroshapes.org","https://bluebrain.github.io/nexus/contexts/metadata.json"]}""")
    }

    "work with a single value" in {
      val original = json"""{"@context" : "https://bluebrain.github.io/nexus/contexts/resolver.json"}"""
      Contexts.updateContext(
        original
      ) shouldEqual (json"""{"@context" : "https://bluebrain.github.io/nexus/contexts/resolvers.json"}""")
    }

  }

}
