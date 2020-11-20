package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OrganizationRejectionsSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with TestHelpers
    with IOValues {

  "An OrganizationRejection" should {

    implicit val rcr: RemoteContextResolution =
      RemoteContextResolution.fixed(contexts.error -> jsonContentOf("/contexts/error.json"))

    val incorrectRev                          = IncorrectRev(2L, 3L)
    val alreadyExists                         = OrganizationAlreadyExists(Label.unsafe("org"))

    "be converted to compacted JSON-LD" in {
      val list = List(
        alreadyExists -> jsonContentOf("/organizations/organization-already-exists-compacted.json"),
        incorrectRev  -> jsonContentOf("/organizations/incorrect-revision-compacted.json")
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        alreadyExists -> jsonContentOf("/organizations/organization-already-exists-expanded.json"),
        incorrectRev  -> jsonContentOf("/organizations/incorrect-revision-expanded.json")
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
