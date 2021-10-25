package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingDataGen
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FilterByTypeSpec
    extends AnyWordSpec
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

  private val tpe = nxv + "Custom"

  private val data = IndexingDataGen
    .fromDataResource(
      nxv + "id",
      project,
      source
    )
    .accepted

  "Filtering by type" should {

    "reject an invalid config" in {
      FilterByType.value.parseAndRun(Some(JsonObject.empty), data).rejected
    }

    "keep data matching the types without modifying it" in {
      FilterByType.value
        .parseAndRun(Some(JsonObject("types" -> Vector(tpe.asJson).asJson)), data)
        .accepted
        .value shouldEqual data
    }

    "filter out data not matching the types" in {
      FilterByType.value
        .parseAndRun(Some(JsonObject("types" -> Vector((nxv + "Another").asJson).asJson)), data)
        .accepted shouldEqual None
    }
  }
}
