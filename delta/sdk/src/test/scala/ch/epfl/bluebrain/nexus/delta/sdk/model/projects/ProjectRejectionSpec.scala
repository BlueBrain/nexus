package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.dummies.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectRejectionSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with TestHelpers
    with IOValues {

  "A ProjectRejection" should {

    implicit val rcr: RemoteContextResolutionDummy =
      RemoteContextResolutionDummy(contexts.error -> jsonContentOf("/contexts/error.json"))

    val incorrectRev                               = IncorrectRev(3L, 2L)
    val alreadyExists                              = ProjectAlreadyExists(ProjectRef(Label.unsafe("org"), Label.unsafe("proj")))

    "be converted to compacted JSON-LD" in {
      val list = List(
        alreadyExists -> json"""{"@type": "ProjectAlreadyExists", "reason": "${alreadyExists.reason}"}""",
        incorrectRev  -> json"""{"@type": "IncorrectRev", "reason": "${incorrectRev.reason}"}"""
      )
      forAll(list) {
        case (rejection, json) => rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        alreadyExists -> json"""[{"@type": ["${nxv + "ProjectAlreadyExists"}"], "${nxv + "reason"}": [{"@value": "${alreadyExists.reason}"} ] } ]""",
        incorrectRev  -> json"""[{"@type": ["${nxv + "IncorrectRev"}"], "${nxv + "reason"}": [{"@value": "${incorrectRev.reason}"} ] } ]"""
      )
      forAll(list) {
        case (rejection, json) => rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
