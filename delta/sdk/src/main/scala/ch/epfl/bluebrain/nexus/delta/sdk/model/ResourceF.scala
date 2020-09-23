package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import cats.Functor
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.JsonObject
import io.circe.syntax._
import monix.bio.{IO, UIO}
import org.apache.jena.iri.IRI

/**
  * A resource representation.
  *
  * @param id         the unique identifier of the resource in a given project
  * @param rev        the revision of the resource
  * @param types      the collection of known types of this resource
  * @param deprecated whether the resource is deprecated of not
  * @param createdAt  the instant when this resource was created
  * @param createdBy  the identity that created this resource
  * @param updatedAt  the last instant when this resource was updated
  * @param updatedBy  the last identity that updated this resource
  * @param schema     the schema reference that this resource conforms to
  * @param value      the resource value
  * @tparam Id the resource id type
  * @tparam A  the resource value type
  */
final case class ResourceF[Id, A](
    id: Id,
    rev: Long,
    types: Set[IRI],
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

  private val resourceFContext = RawJsonLdContext(Vocabulary.contexts.resource.asJson)

  implicit def encoderResourceF[A: JsonLdEncoder](implicit base: BaseUri): JsonLdEncoder[ResourceF[IRI, A]] =
    resourceFUnitJsonLdEncoder.compose(rf => (rf.void, rf.value), _.id)

  implicit def resourceFUnitJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourceF[IRI, Unit]] = {
    new JsonLdEncoder[ResourceF[IRI, Unit]] {
      override def apply(rf: ResourceF[IRI, Unit]): IO[RdfError, JsonLd] =
        UIO.pure {
          JsonLd.compactedUnsafe(
            JsonObject.fromIterable(
              List(
                "@id"            -> rf.id.asJson,
                "_rev"           -> rf.rev.asJson,
                "_deprecated"    -> rf.deprecated.asJson,
                "_createdAt"     -> rf.createdAt.asJson,
                "_createdBy"     -> rf.createdBy.id(base).asJson,
                "_updatedAt"     -> rf.updatedAt.asJson,
                "_updatedBy"     -> rf.updatedBy.id(base).asJson,
                "_constrainedBy" -> rf.schema.iri.asJson
              ) ++ {
                rf.types.toList match {
                  case Nil         => Nil
                  case head :: Nil => List("@type" -> head.asJson)
                  case more        => List("@type" -> more.asJson)
                }
              }
            ),
            resourceFContext,
            rf.id
          )
        }

      override val contextValue: RawJsonLdContext = resourceFContext
    }
  }

}
