package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import java.time.Instant

import cats.Functor
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoderSpec.{Permissions, ResourceF, encoderPermissions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.{Fixtures, RdfError}
import io.circe.JsonObject
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.iri.IRI
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdEncoderSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "a JsonLdEncoder" should {
    val permissions: Permissions = Set("read", "write", "execute")
    val value: ResourceF[Permissions] = ResourceF(iri, 1L, deprecated = false, Instant.EPOCH, permissions)
    val compacted   = jsonContentOf("encoder/compacted.json")

    "return JsonLd" in {
      println(value.toJsonLd.accepted.json.spaces2)
      value.toJsonLd.accepted.json.spaces2 shouldEqual compacted.spaces2
    }
  }
}

object JsonLdEncoderSpec {

  type Permissions = Set[String]

  final case class ResourceF[A](id: IRI, rev: Long, deprecated: Boolean, createdAt: Instant, value: A)

  object ResourceF {

    implicit val functorResourceF: Functor[ResourceF] = new Functor[ResourceF] {
      override def map[A, B](fa: ResourceF[A])(f: A => B): ResourceF[B] = fa.copy(value = f(fa.value))
    }

    implicit val encoderResourceFUnit: JsonLdEncoder[ResourceF[Unit]] =
      new JsonLdEncoder[ResourceF[Unit]] {
        private val ctx = RawJsonLdContext(iri"http://example.com/contexts/resource.json".asJson)

        override def apply(value: ResourceF[Unit]): IO[RdfError, JsonLd] = {
          val obj = JsonObject.fromIterable(
            List(
              "@id"        -> value.id.asJson,
              "rev"        -> value.rev.asJson,
              "deprecated" -> value.deprecated.asJson,
              "createdAt"  -> value.createdAt.asJson
            )
          )
          IO.pure(JsonLd.compactedUnsafe(obj, ctx, value.id))
        }
      }

    implicit def encoderResourceF[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
      encoderResourceFUnit.compose(metadata => (metadata.void, metadata.value), _.id)
  }

  implicit val encoderPermissions: JsonLdEncoder[Permissions] =
    new JsonLdEncoder[Permissions] {

      private val ctx = RawJsonLdContext(iri"http://example.com/contexts/permissions.json".asJson)

      override def apply(value: Permissions): IO[RdfError, JsonLd] = {
        val obj = JsonObject.empty.add("permissions", value.asJson)
        IO.pure(JsonLd.compactedUnsafe(obj, ctx, iri""))
      }
    }
}
