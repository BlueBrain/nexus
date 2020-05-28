package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.Node.BNode
import ch.epfl.bluebrain.nexus.rdf.{Graph, RdfSpec}
import io.circe.Json

class JenaWriterCleanupSpec extends RdfSpec {

  "JenaWriterCleanup" should {

    "convert types in Json" in {

      new JenaWriterCleanup(Json.obj())
        .cleanFromJson(jsonContentOf("/jena/json-with-types.json"), Graph(BNode())) shouldEqual jsonContentOf(
        "/jena/json-without-types.json"
      )

    }
  }

}
