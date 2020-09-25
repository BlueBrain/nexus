package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import cats.Functor
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.JsonObject
import io.circe.syntax._
import monix.bio.IO

/**
  * A resource representation.
  *
  * @param id         the unique identifier of the resource in a given project
  * @param rev        the revision of the resource
  * @param types      the collection of known types of this resource
  * @param deprecated whether the resource is deprecated of not
  * @param createdAt  the instant when this resource was created
  * @param createdBy  the subject that created this resource
  * @param updatedAt  the last instant when this resource was updated
  * @param updatedBy  the last subject that updated this resource
  * @param schema     the schema reference that this resource conforms to
  * @param value      the resource value
  * @tparam Id the resource id type
  * @tparam A  the resource value type
  */
final case class ResourceF[Id, A](
    id: Id,
    rev: Long,
    types: Set[Iri],
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject,
    schema: ResourceRef,
    value: A
) {

  /**
    * Maps the value of the resource using the supplied function f.
    *
    * @param f  the function to apply to transform the current resource value
    * @return a new resource with a value of type B
    */
  def map[B](f: A => B): ResourceF[Id, B] =
    ResourceF(id, rev, types, deprecated, createdAt, createdBy, updatedAt, updatedBy, schema, f(value))
}

object ResourceF {
  implicit def resourceFunctor[Id]: Functor[ResourceF[Id, *]] =
    new Functor[ResourceF[Id, *]] {
      override def map[A, B](fa: ResourceF[Id, A])(f: A => B): ResourceF[Id, B] = fa.map(f)
    }

  implicit def resourceFAJsonLdEncoder[A: JsonLdEncoder](implicit base: BaseUri): JsonLdEncoder[ResourceF[Iri, A]] =
    JsonLdEncoder.compose(rf => (rf.void, rf.value, rf.id))

  implicit def resourceFUnitJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourceF[Iri, Unit]] =
    new JsonLdEncoder[ResourceF[Iri, Unit]] {
      override def apply(rf: ResourceF[Iri, Unit]): IO[RdfError, JsonLd] =
        IO.pure {
          val obj          = JsonObject.empty
            .add(keywords.id, rf.id.asJson)
            .add("_rev", rf.rev.asJson)
            .add("_deprecated", rf.deprecated.asJson)
            .add("_createdAt", rf.createdAt.asJson)
            .add("_createdBy", rf.createdBy.id.asJson)
            .add("_updatedAt", rf.updatedAt.asJson)
            .add("_updatedBy", rf.updatedBy.id.asJson)
            .add("_constrainedBy", rf.schema.iri.asJson)
          val objWithTypes = rf.types.take(2).toList match {
            case Nil         => obj
            case head :: Nil => obj.add(keywords.tpe, head.stripPrefix(nxv.base).asJson)
            case _           => obj.add(keywords.tpe, rf.types.map(_.stripPrefix(nxv.base)).asJson)
          }
          JsonLd.compactedUnsafe(objWithTypes, defaultContext, rf.id)
        }

      override val defaultContext: RawJsonLdContext = RawJsonLdContext(contexts.resource.asJson)
    }
}
