package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OrganizationSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with CirceLiteral
    with OptionValues {

  "An Organization" should {
    implicit val res: RemoteContextResolution =
      RemoteContextResolution.fixed(contexts.organizations -> jsonContentOf("contexts/organizations.json"))

    val organization                          = OrganizationGen.organization("myorg", description = Some("My description"))
    val compacted                             =
      json"""{"@context": "${contexts.organizations}", "_label": "${organization.label}", "_uuid": "${organization.uuid}", "description": "${organization.description.value}"}"""

    val expanded =
      json"""[{"${nxv.label.iri}" : [{"@value" : "${organization.label}"} ], "${nxv.uuid.iri}" : [{"@value" : "${organization.uuid}"} ], "${nxv + "description"}" : [{"@value" : "${organization.description.value}"} ] } ]"""

    "be converted to Json-LD Compacted" in {
      organization.toCompactedJsonLd.accepted.json shouldEqual compacted
    }

    "be converted to Json-LD expanded" in {
      organization.toExpandedJsonLd.accepted.json shouldEqual expanded
    }
  }
}
