package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingDataGen
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FilterBySchemaSpec
    extends AnyWordSpec
    with CirceLiteral
    with IOValues
    with Matchers
    with TestHelpers
    with OptionValues
    with EitherValuable {

  implicit private val cl: ClassLoader      = getClass.getClassLoader
  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val res: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> ContextValue.fromFile("contexts/metadata.json").accepted
  )

  private val project = ProjectRef.unsafe("org", "proj")

  private val source = jsonContentOf("resource/source.json")

  private val schema = nxv + "Schema"

  private val data = IndexingDataGen
    .fromDataResource(
      nxv + "id",
      project,
      source,
      schema = ResourceRef.Latest(schema)
    )
    .accepted

  "Filtering by schema" should {

    "reject an invalid config" in {
      FilterBySchema.value.parseAndRun(Some(ExpandedJsonLd.empty), data).rejected
    }

    "keep data matching the schemas without modifying it" in {
      FilterBySchema.value
        .parseAndRun(FilterBySchema.definition(Set(schema)).config, data)
        .accepted
        .value shouldEqual data
    }

    "filter out data not matching the schemas" in {
      FilterBySchema.value
        .parseAndRun(FilterBySchema.definition(Set(nxv + "Another")).config, data)
        .accepted shouldEqual None
    }
  }
}
