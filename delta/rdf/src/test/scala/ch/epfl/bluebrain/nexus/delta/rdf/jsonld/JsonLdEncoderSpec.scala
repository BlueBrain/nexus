package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.dummies.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoderSpec.{encoderPermissions, Permissions, ResourceF}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.{Fixtures, RdfError}
import io.circe.JsonObject
import io.circe.syntax._
import monix.bio.IO
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdEncoderSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "a JsonLdEncoder" when {
    val iri                      = iri"http://nexus.example.com/john-doé"
    val permissions: Permissions = Set("read", "write", "execute")
    val permissionsContext       = json"""{ "@context": {"permissions": "${nxv + "permissions"}"} }"""

    "dealing with Permissions" should {

      implicit val remoteResolution: RemoteContextResolutionDummy =
        RemoteContextResolutionDummy(contexts.permissions -> permissionsContext)
      val mergedCtx                                               = RawJsonLdContext(permissionsContext.topContextValueOrEmpty)

      val compacted         = json"""{ "@context": "${contexts.permissions}", "permissions": [ "read", "write", "execute" ] }"""
      val expanded          =
        json"""[{"${nxv + "permissions"}": [{"@value": "read"}, {"@value": "write"}, {"@value": "execute"} ] } ]"""
      def dot(bnode: BNode) = s"""digraph "${bnode.rdfFormat}" {
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

      "return a compacted Json-LD format using a custom @context" in {
        permissions.toCompactedJsonLd(mergedCtx).accepted.json.removeKeys(keywords.context) shouldEqual
          compacted.removeKeys(keywords.context)
      }

      "return an expanded Json-LD format" in {
        permissions.toExpandedJsonLd.accepted.json shouldEqual expanded
      }

      "return a DOT format" in {
        val result = permissions.toDot.accepted
        result.toString should equalLinesUnordered(dot(result.rootNode.asBNode.value))
      }

      "return a DOT format using a custom @context" in {
        val result = permissions.toDot(mergedCtx).accepted
        result.toString should equalLinesUnordered(dot(result.rootNode.asBNode.value))
      }

      "return a NTriples format" in {
        val result = permissions.toNTriples.accepted
        result.toString should equalLinesUnordered(ntriples(result.rootNode.asBNode.value))
      }
    }

    "dealing with a ResourceF of Permissions" should {

      val resourceContext               = json"""{ "@context": {"@vocab": "${nxv.base}"} }"""
      val mergedCtx                     = RawJsonLdContext(permissionsContext.addContext(resourceContext).topContextValueOrEmpty)
      val value: ResourceF[Permissions] = ResourceF(iri, 1L, deprecated = false, Instant.EPOCH, permissions)
      val compacted                     = jsonContentOf("encoder/compacted.json")
      val expanded                      = jsonContentOf("encoder/expanded.json")
      val dot                           = contentOf("encoder/dot.dot")
      val ntriples                      = contentOf("encoder/ntriples.nt")

      implicit val remoteResolution: RemoteContextResolutionDummy =
        RemoteContextResolutionDummy(contexts.permissions -> permissionsContext, contexts.resource -> resourceContext)

      "return a compacted Json-LD format" in {

        value.toCompactedJsonLd.accepted.json shouldEqual compacted
      }

      "return a compacted Json-LD format using a custom @context" in {
        value.toCompactedJsonLd(mergedCtx).accepted.json.removeKeys(keywords.context) shouldEqual
          compacted.removeKeys(keywords.context)
      }

      "return an expanded Json-LD format" in {
        value.toExpandedJsonLd.accepted.json shouldEqual expanded
      }

      "return a DOT format" in {
        value.toDot.accepted.toString should equalLinesUnordered(dot)
      }

      "return a DOT format using a custom @context" in {
        value.toDot(mergedCtx).accepted.toString should equalLinesUnordered(dot)
      }

      "return a NTriples format" in {
        value.toNTriples.accepted.toString should equalLinesUnordered(ntriples)
      }
    }
  }
}

object JsonLdEncoderSpec {

  type Permissions = Set[String]

  final case class ResourceF[A](id: Iri, rev: Long, deprecated: Boolean, createdAt: Instant, value: A) {
    def unit: ResourceF[Unit] = copy(value = ())
  }

  object ResourceF {

    implicit val encoderResourceFUnit: JsonLdEncoder[ResourceF[Unit]] =
      new JsonLdEncoder[ResourceF[Unit]] {

        val defaultContext: RawJsonLdContext = RawJsonLdContext(contexts.resource.asJson)

        override def apply(value: ResourceF[Unit]): IO[RdfError, JsonLd] = {
          val obj = JsonObject.empty
            .add("@id", value.id.asJson)
            .add("rev", value.rev.asJson)
            .add("deprecated", value.deprecated.asJson)
            .add("createdAt", value.createdAt.asJson)
          IO.pure(JsonLd.compactedUnsafe(obj, defaultContext, value.id))
        }
      }

    implicit def encoderResourceF[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
      JsonLdEncoder.compose(resource => (resource.unit, resource.value, resource.id))
  }

  implicit val encoderPermissions: JsonLdEncoder[Permissions] =
    new JsonLdEncoder[Permissions] {

      val defaultContext: RawJsonLdContext = RawJsonLdContext(contexts.permissions.asJson)

      override def apply(value: Permissions): IO[RdfError, JsonLd] = {
        val obj = JsonObject.empty.add("permissions", value.asJson)
        IO.pure(JsonLd.compactedUnsafe(obj, defaultContext, BNode.random))
      }
    }
}
