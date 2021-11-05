package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf, rdfs, schema, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._

class IncludePredicatesSpec extends PipeBaseSpec {

  "Include by schema" should {

    "reject an invalid config" in {
      IncludePredicates.pipe.parseAndRun(Some(ExpandedJsonLd.empty), sampleData)
    }

    "keep only matching predicates" in {
      val result =
        IncludePredicates.pipe.parseAndRun(IncludePredicates.defaultLabels, sampleData).accepted.value

      val expectedGraph = Graph
        .empty(sampleData.id)
        .add(rdf.tpe, nxv + "Custom")
        .add(schema.name, "Alex")
        .add(rdfs.label, "Alex C.")
        .add(skos.prefLabel, "Alex Campbell")

      result shouldEqual sampleData.copy(
        graph = expectedGraph,
        types = sampleData.types
      )
    }
  }

}
