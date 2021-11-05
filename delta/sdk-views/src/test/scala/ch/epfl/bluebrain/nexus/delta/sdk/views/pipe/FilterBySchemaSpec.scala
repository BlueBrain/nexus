package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef

class FilterBySchemaSpec extends PipeBaseSpec {

  private val schema = nxv + "Schema"

  private val validatedData = sampleData.copy(
    schema = ResourceRef.Latest(schema)
  )

  "Filtering by schema" should {

    "reject an invalid config" in {
      FilterBySchema.pipe.parseAndRun(Some(ExpandedJsonLd.empty), validatedData).rejected
    }

    "keep data matching the schemas without modifying it" in {
      FilterBySchema.pipe
        .parseAndRun(FilterBySchema(Set(schema)), validatedData)
        .accepted
        .value shouldEqual validatedData
    }

    "filter out data not matching the schemas" in {
      FilterBySchema.pipe
        .parseAndRun(FilterBySchema(Set(nxv + "Another")), validatedData)
        .accepted shouldEqual None
    }
  }
}
