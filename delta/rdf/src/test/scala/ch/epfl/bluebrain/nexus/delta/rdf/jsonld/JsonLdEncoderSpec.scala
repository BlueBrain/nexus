package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoderSpec.{Permissions, ResourceF}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdEncoderSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "a JsonLdEncoder" when {
    val iri                      = iri"http://nexus.example.com/john-doé"
    val permissions: Permissions = Permissions(Set("read", "write", "execute"))
    val permissionsContext       = json"""{ "@context": {"permissions": "${nxv + "permissions"}"} }"""

    "dealing with Permissions" should {

      implicit val remoteResolution: RemoteContextResolution =
        RemoteContextResolution.fixed(contexts.permissions -> permissionsContext)

      val compacted                                          = json"""{ "@context": "${contexts.permissions}", "permissions": [ "read", "write", "execute" ] }"""
      val expanded                                           =
        json"""[{"${nxv + "permissions"}": [{"@value": "read"}, {"@value": "write"}, {"@value": "execute"} ] } ]"""
      def dot(bnode: BNode)                                  = s"""digraph "${bnode.rdfFormat}" {
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

    "dealing with a ResourceF of Permissions" should {

      val resourceContext               = json"""{ "@context": {"@vocab": "${nxv.base}"} }"""
      val value: ResourceF[Permissions] = ResourceF(iri, 1L, deprecated = false, Instant.EPOCH, permissions)
      val compacted                     = jsonContentOf("encoder/compacted.json")
      val expanded                      = jsonContentOf("encoder/expanded.json")
      val dot                           = contentOf("encoder/dot.dot")
      val ntriples                      = contentOf("encoder/ntriples.nt")

      implicit val remoteResolution: RemoteContextResolution =
        RemoteContextResolution.fixed(contexts.permissions -> permissionsContext, contexts.resource -> resourceContext)

      "return a compacted Json-LD format" in {
        value.toCompactedJsonLd.accepted.json shouldEqual compacted
      }

      "return an expanded Json-LD format" in {
        value.toExpandedJsonLd.accepted.json shouldEqual expanded
      }

      "return a DOT format" in {
        value.toDot.accepted.toString should equalLinesUnordered(dot)
      }

      "return a NTriples format" in {
        value.toNTriples.accepted.toString should equalLinesUnordered(ntriples)
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
      JsonLdEncoder.fromCirce(id = BNode.random, iriContext = contexts.permissions)
  }

  final case class ResourceF[A](id: Iri, rev: Long, deprecated: Boolean, createdAt: Instant, value: A) {
    def unit: ResourceF[Unit] = copy(value = ())
  }

  object ResourceF {

    implicit private val resourceFUnitEncoder: Encoder.AsObject[ResourceF[Unit]] =
      Encoder.AsObject.instance { r =>
        JsonObject.empty
          .add("@id", r.id.asJson)
          .add("rev", r.rev.asJson)
          .add("deprecated", r.deprecated.asJson)
          .add("createdAt", r.createdAt.asJson)
      }

    implicit final val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
      JsonLdEncoder.fromCirce(_.id, iriContext = contexts.resource)

    implicit def encoderResourceF[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
      JsonLdEncoder.compose(resource => (resource.unit, resource.value, resource.id))
  }
}
