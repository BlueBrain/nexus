package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.dummies.RemoteContextResolutionDummy
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

    implicit val rcr: RemoteContextResolutionDummy =
      RemoteContextResolutionDummy(contexts.error -> jsonContentOf("/contexts/error.json"))

    val incorrectRev                               = IncorrectRev(2L, 3L)
    val alreadyExists                              = OrganizationAlreadyExists(Label.unsafe("org"))

    "be converted to compacted JSON-LD" in {
      val list = List(
        alreadyExists -> json"""{"@type": "OrganizationAlreadyExists", "reason": "${alreadyExists.reason}"}""",
        incorrectRev  -> json"""{"@type": "IncorrectRev", "reason": "${incorrectRev.reason}"}"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        alreadyExists -> json"""[{"@type": ["${nxv + "OrganizationAlreadyExists"}"], "${nxv + "reason"}": [{"@value": "${alreadyExists.reason}"} ] } ]""",
        incorrectRev  -> json"""[{"@type": ["${nxv + "IncorrectRev"}"], "${nxv + "reason"}": [{"@value": "${incorrectRev.reason}"} ] } ]"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
