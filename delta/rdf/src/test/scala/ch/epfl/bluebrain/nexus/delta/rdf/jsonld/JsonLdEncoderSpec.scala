package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoderSpec.Permissions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdEncoderSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "a JsonLdEncoder" when {
    val permissions: Permissions = Permissions(Set("read", "write", "execute"))
    val permissionsContext       = json"""{ "@context": {"permissions": "${nxv + "permissions"}"} }"""

    "dealing with Permissions" should {

      implicit val remoteResolution: RemoteContextResolution =
        RemoteContextResolution.fixed(contexts.permissions -> permissionsContext)

      val compacted                                          = json"""{ "@context": "${contexts.permissions}", "permissions": [ "read", "write", "execute" ] }"""
      val expanded                                           =
        json"""[{"${nxv + "permissions"}": [{"@value": "read"}, {"@value": "write"}, {"@value": "execute"} ] } ]"""

      def dot(bnode: BNode) =
        s"""digraph "${bnode.rdfFormat}" {
           |  "${bnode.rdfFormat}" -> "execute" [label = "permissions"]
           |  "${bnode.rdfFormat}" -> "write" [label = "permissions"]
           |  "${bnode.rdfFormat}" -> "read" [label = "permissions"]
           |}""".stripMargin

      def ntriples(bnode: BNode) =
        s"""${bnode.rdfFormat} <${nxv + "permissions"}> "execute" .
           |${bnode.rdfFormat} <${nxv + "permissions"}> "write" .
           |${bnode.rdfFormat} <${nxv + "permissions"}> "read" .
           |""".stripMargin

      "return a compacted Json-LD format" in {
        permissions.toCompactedJsonLd.accepted.json shouldEqual compacted
      }

      "return an expanded Json-LD format" in {
        permissions.toExpandedJsonLd.accepted.json shouldEqual expanded
      }

      "return a DOT format" in {
        val result = permissions.toDot.accepted
        result.toString should equalLinesUnordered(dot(result.rootNode.asBNode.value))
      }

      "return a NTriples format" in {
        val result = permissions.toNTriples.accepted
        result.toString should equalLinesUnordered(ntriples(result.rootNode.asBNode.value))
      }
    }
  }
}

object JsonLdEncoderSpec {

  final case class Permissions(permissions: Set[String])

  object Permissions {
    implicit private val permissionsEncoder: Encoder.AsObject[Permissions] =
      Encoder.AsObject.instance(p => JsonObject.empty.add("permissions", p.permissions.asJson))

    implicit final val permissionsJsonLdEncoder: JsonLdEncoder[Permissions] =
      JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.permissions))
  }
}
