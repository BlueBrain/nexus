package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.dummies.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsRejectionSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with TestHelpers
    with IOValues {

  "A PermissionsRejection" should {

    implicit val rcr: RemoteContextResolutionDummy =
      RemoteContextResolutionDummy(contexts.error -> jsonContentOf("/contexts/error.json"))

    val incorrectRev                               = IncorrectRev(3L, 2L)
    val cannotReplace                              = CannotReplaceWithEmptyCollection

    "be converted to compacted JSON-LD" in {
      val list = List(
        cannotReplace -> json"""{"@type": "CannotReplaceWithEmptyCollection", "reason": "${cannotReplace.reason}"}""",
        incorrectRev  -> json"""{"@type": "IncorrectRev", "reason": "${incorrectRev.reason}"}"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        cannotReplace -> json"""[{"@type": ["${nxv + "CannotReplaceWithEmptyCollection"}"], "${nxv + "reason"}": [{"@value": "${cannotReplace.reason}"} ] } ]""",
        incorrectRev  -> json"""[{"@type": ["${nxv + "IncorrectRev"}"], "${nxv + "reason"}": [{"@value": "${incorrectRev.reason}"} ] } ]"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
