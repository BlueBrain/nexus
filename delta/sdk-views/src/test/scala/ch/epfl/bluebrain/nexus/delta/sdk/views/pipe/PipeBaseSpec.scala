package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingDataGen
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import io.circe.Json
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait PipeBaseSpec
    extends AnyWordSpecLike
    with TestHelpers
    with CirceLiteral
    with IOValues
    with Matchers
    with OptionValues
    with EitherValuable {

  implicit def cl: ClassLoader = getClass.getClassLoader

  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val res: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> ContextValue.fromFile("contexts/metadata.json").accepted
  )

  val project: ProjectRef = ProjectRef.unsafe("org", "proj")

  val sampleSource: Json = jsonContentOf("resource/source.json")

  val sampleData: IndexingData = IndexingDataGen
    .fromDataResource(
      nxv + "id",
      project,
      sampleSource
    )
    .accepted

}
