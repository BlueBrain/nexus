package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectFieldsSpec extends AnyWordSpecLike with Matchers with EitherValuable {

  implicit lazy val baseUri: BaseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))

  "Generating default" should {

    val fields = ProjectFields(None, ApiMappings.empty, None, None)

    "Generate the expected default defaultBase" in {
      val defaultBase = fields.baseOrGenerated(ProjectRef(Label.unsafe("org"), Label.unsafe("proj")))

      defaultBase.value shouldEqual iri"http://localhost:8080/v1/resources/org/proj/_/"
    }
    "Generate the expected default vocab" in {
      val fields = ProjectFields(None, ApiMappings.empty, None, None)

      val defaultVocab = fields.vocabOrGenerated(ProjectRef(Label.unsafe("org"), Label.unsafe("proj")))

      defaultVocab.value shouldEqual iri"http://localhost:8080/v1/vocabs/org/proj/"
    }

  }

}
