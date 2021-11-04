package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import io.circe.Json
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._

class SourceAsTextSpec extends PipeBaseSpec {

  "Source as test" should {
    "add source as a field in the graph" in {
      SourceAsText.pipe.parseAndRun(None, sampleData).accepted.value shouldEqual sampleData.copy(
        graph = sampleData.graph.add(nxv.originalSource.iri, sampleData.source.noSpaces),
        source = Json.obj()
      )
    }
  }

}
