package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class PermissionsRejectionSpec extends CatsEffectSpec with CirceLiteral with Fixtures {

  "A PermissionsRejection" should {

    val incorrectRev  = IncorrectRev(2, 3)
    val cannotReplace = CannotReplaceWithEmptyCollection

    "be converted to compacted JSON-LD" in {
      val list = List(
        cannotReplace -> json"""{"@type": "CannotReplaceWithEmptyCollection", "reason": "${cannotReplace.reason}"}""",
        incorrectRev  -> jsonContentOf("permissions/incorrect-revision-compacted.json")
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        cannotReplace -> json"""[{"@type": ["${nxv + "CannotReplaceWithEmptyCollection"}"], "${nxv + "reason"}": [{"@value": "${cannotReplace.reason}"} ] } ]""",
        incorrectRev  -> jsonContentOf("permissions/incorrect-revision-expanded.json")
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
