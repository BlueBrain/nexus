package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

class ProjectFieldsSpec extends BaseSpec {

  implicit lazy val baseUri: BaseUri = BaseUri.unsafe("http://localhost:8080", "v1")

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
