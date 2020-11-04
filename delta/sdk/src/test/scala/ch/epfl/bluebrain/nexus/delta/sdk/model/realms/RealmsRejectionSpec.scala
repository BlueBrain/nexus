package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RealmsRejectionSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with TestHelpers
    with IOValues {

  "A RealmsRejection" should {

    implicit val rcr: RemoteContextResolution =
      RemoteContextResolution.fixed(contexts.error -> jsonContentOf("/contexts/error.json"))

    val incorrectRev                          = IncorrectRev(3L, 2L)
    val alreadyExists                         = RealmAlreadyExists(Label.unsafe("name"))

    "be converted to compacted JSON-LD" in {
      val list = List(
        alreadyExists -> json"""{"@type": "RealmAlreadyExists", "reason": "${alreadyExists.reason}"}""",
        incorrectRev  -> json"""{"@type": "IncorrectRev", "reason": "${incorrectRev.reason}"}"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        alreadyExists -> json"""[{"@type": ["${nxv + "RealmAlreadyExists"}"], "${nxv + "reason"}": [{"@value": "${alreadyExists.reason}"} ] } ]""",
        incorrectRev  -> json"""[{"@type": ["${nxv + "IncorrectRev"}"], "${nxv + "reason"}": [{"@value": "${incorrectRev.reason}"} ] } ]"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
