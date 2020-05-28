package ch.epfl.bluebrain.nexus.rdf.jena

import ch.epfl.bluebrain.nexus.rdf.RdfSpec

import scala.io.Source

class JenaSpec extends RdfSpec {

  private def loadJsonString(): String =
    Source.fromInputStream(getClass.getResourceAsStream("/a.json")).mkString

  "The Jena compat" should {
    "correctly parse a json string" in {
      val parsedModel = Jena.parse(loadJsonString()).rightValue
      parsedModel isIsomorphicWith Fixture.model shouldEqual true
    }
    "fail to parse a json on syntax error" in {
      Jena.parse(loadJsonString() + "a").leftValue
    }
  }
}
