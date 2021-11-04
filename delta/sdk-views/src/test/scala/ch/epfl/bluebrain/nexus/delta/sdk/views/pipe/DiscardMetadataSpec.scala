package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph

class DiscardMetadataSpec extends PipeBaseSpec {

  "Discard metadata" should {
    "remove all metadata" in {
      DiscardMetadata.pipe.parseAndRun(None, sampleData).accepted.value shouldEqual sampleData.copy(metadataGraph =
        Graph.empty
      )
    }
  }

}
