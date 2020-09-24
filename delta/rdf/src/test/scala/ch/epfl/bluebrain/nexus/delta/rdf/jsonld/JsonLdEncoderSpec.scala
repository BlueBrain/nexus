package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoderSpec.{encoderPermissions, Permissions, ResourceF}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.{Fixtures, RdfError, RemoteContextResolutionDummy}
import io.circe.JsonObject
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.iri.IRI
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdEncoderSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "a JsonLdEncoder" should {
    val iri                           = iri"http://nexus.example.com/john-doé"
    val permissions: Permissions      = Set("read", "write", "execute")
    val value: ResourceF[Permissions] = ResourceF(iri, 1L, deprecated = false, Instant.EPOCH, permissions)

    implicit val remoteResolution: RemoteContextResolutionDummy = new RemoteContextResolutionDummy(
      Map(
        contexts.permissions -> json"""{ "@context": {"permissions": "${nxv + "permissions"}"} }""",
        contexts.resource    -> json"""{ "@context": {"@vocab": "${nxv.base}"} }"""
      )
    )

    "return a compacted Json-LD format" in {
      val compacted = jsonContentOf("encoder/compacted.json")
      value.toCompactedJsonLd.accepted.json shouldEqual compacted
    }

    "return an expanded Json-LD format" in {
      val expanded = jsonContentOf("encoder/expanded.json")
      value.toExpandedJsonLd.accepted.json shouldEqual expanded
    }

    "return a DOT format" in {
      val dot = contentOf("encoder/dot.dot")
      value.toDot.accepted.toString should equalLinesUnordered(dot)
    }

    "return a NTriples format" in {
      val ntriples = contentOf("encoder/ntriples.nt")
      value.toNTriples.accepted.toString should equalLinesUnordered(ntriples)
    }
  }
}

object JsonLdEncoderSpec {

  type Permissions = Set[String]

  final case class ResourceF[A](id: IRI, rev: Long, deprecated: Boolean, createdAt: Instant, value: A) {
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
        IO.pure(JsonLd.compactedUnsafe(obj, defaultContext, iri""))
      }
    }
}
