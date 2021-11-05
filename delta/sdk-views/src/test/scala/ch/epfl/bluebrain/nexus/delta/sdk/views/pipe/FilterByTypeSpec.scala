package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd

class FilterByTypeSpec extends PipeBaseSpec {

  "Filtering by type" should {

    "reject an invalid config" in {
      FilterByType.pipe.parseAndRun(Some(ExpandedJsonLd.empty), sampleData).rejected
    }

    "keep data matching the types without modifying it" in {
      FilterByType.pipe
        .parseAndRun(FilterByType(Set(nxv + "Custom")).config, sampleData)
        .accepted
        .value shouldEqual sampleData
    }

    "filter out data not matching the types" in {
      FilterByType.pipe
        .parseAndRun(FilterByType(Set(nxv + "Another")).config, sampleData)
        .accepted shouldEqual None
    }
  }
}
