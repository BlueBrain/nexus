package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{IncorrectRev, RealmAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class RealmsRejectionSpec extends CatsEffectSpec with CirceLiteral with Fixtures {

  "A RealmsRejection" should {

    val incorrectRev  = IncorrectRev(2, 3)
    val alreadyExists = RealmAlreadyExists(Label.unsafe("name"))

    "be converted to compacted JSON-LD" in {
      val list = List(
        alreadyExists -> json"""{"@type": "RealmAlreadyExists", "reason": "${alreadyExists.reason}"}""",
        incorrectRev  -> jsonContentOf("/realms/incorrect-revision-compacted.json")
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        alreadyExists -> json"""[{"@type": ["${nxv + "RealmAlreadyExists"}"], "${nxv + "reason"}": [{"@value": "${alreadyExists.reason}"} ] } ]""",
        incorrectRev  -> jsonContentOf("/realms/incorrect-revision-expanded.json")
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
