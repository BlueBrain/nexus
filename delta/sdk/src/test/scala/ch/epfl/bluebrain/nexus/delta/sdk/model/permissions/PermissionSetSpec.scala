package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.dummies.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers, TestMatchers}
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionSetSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with TestHelpers
    with EitherValuable
    with CirceLiteral
    with IOValues
    with TestMatchers {

  "A Permission set" should {

    implicit val remoteResolution: RemoteContextResolutionDummy = RemoteContextResolutionDummy(
      contexts.permissions -> json"""{ "@context": {"permissions": "${nxv + "permissions"}"} }""",
      contexts.resource    -> json"""{ "@context": {"@vocab": "${nxv.base}"} }"""
    )

    val set  = PermissionSet(Set(acls.read, acls.write))
    val json = json"""{"permissions": ["${acls.read}", "${acls.write}"]}"""

    "be converted to Json" in {
      set.asJson shouldEqual json
    }

    "be constructed from Json" in {
      json.as[PermissionSet].rightValue shouldEqual set
    }

    "be converted to Json-LD compacted" in {
      set.toCompactedJsonLd.accepted.json shouldEqual
        json"""{ "@context": "${contexts.permissions}", "permissions": ["${acls.read}", "${acls.write}"] }"""
    }

    "be converted to Json-LD expanded" in {
      set.toExpandedJsonLd.accepted.json shouldEqual
        json"""[ { "${nxv + "permissions"}": [  {"@value": "${acls.read}"},  {"@value": "${acls.write}"} ] } ]"""
    }

    "be converted to Dot format" in {
      val result = set.toDot.accepted
      result.toString should equalLinesUnordered(s"""
           |digraph "${result.rootNode.rdfFormat}" {
           |  "${result.rootNode.rdfFormat}" -> "${acls.read}" [label = "permissions"]
           |  "${result.rootNode.rdfFormat}" -> "${acls.write}" [label = "permissions"]
           |}
           |""".stripMargin)
    }

    "be converted to NTriples format" in {
      val result = set.toNTriples.accepted
      result.toString should equalLinesUnordered(s"""
          |${result.rootNode.rdfFormat} <${nxv + "permissions"}> "${acls.read}" .
          |${result.rootNode.rdfFormat} <${nxv + "permissions"}> "${acls.write}" .
          |""".stripMargin)
    }
  }
}
