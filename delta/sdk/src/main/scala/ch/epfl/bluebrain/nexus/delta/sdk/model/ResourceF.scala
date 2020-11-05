package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import cats.Functor
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * A resource representation.
  *
  * @param id         the resource id
  * @param accessUrl  the resource access Url
  * @param rev        the revision of the resource
  * @param types      the collection of known types of this resource
  * @param deprecated whether the resource is deprecated of not
  * @param createdAt  the instant when this resource was created
  * @param createdBy  the subject that created this resource
  * @param updatedAt  the last instant when this resource was updated
  * @param updatedBy  the last subject that updated this resource
  * @param schema     the schema reference that this resource conforms to
  * @param value      the resource value
  * @tparam A the resource value type
  */
final case class ResourceF[A](
    id: Iri,
    accessUrl: AccessUrl,
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
  def map[B](f: A => B): ResourceF[B] =
    copy(value = f(value))
}

object ResourceF {
  implicit val resourceFunctor: Functor[ResourceF] =
    new Functor[ResourceF] {
      override def map[A, B](fa: ResourceF[A])(f: A => B): ResourceF[B] = fa.map(f)
    }

  implicit final private def resourceFUnitEncoder(implicit
      mappings: ApiMappings,
      base: BaseUri
  ): Encoder.AsObject[ResourceF[Unit]] =
    Encoder.AsObject.instance { r =>
      val obj = JsonObject.empty
        .add(keywords.id, r.id.asJson)
        .add("_rev", r.rev.asJson)
        .add("_deprecated", r.deprecated.asJson)
        .add("_createdAt", r.createdAt.asJson)
        .add("_createdBy", r.createdBy.id.asJson)
        .add("_updatedAt", r.updatedAt.asJson)
        .add("_updatedBy", r.updatedBy.id.asJson)
        .add("_constrainedBy", r.schema.iri.asJson)
        .add("_self", r.accessUrl.shortForm(mappings).asJson)
      r.types.take(2).toList match {
        case Nil         => obj
        case head :: Nil => obj.add(keywords.tpe, head.stripPrefix(nxv.base).asJson)
        case _           => obj.add(keywords.tpe, r.types.map(_.stripPrefix(nxv.base)).asJson)
      }
    }

  implicit def resourceFAEncoder[A: Encoder.AsObject](implicit
      base: BaseUri,
      mappings: ApiMappings = ApiMappings.empty
  ): Encoder.AsObject[ResourceF[A]] =
    Encoder.AsObject.instance { r =>
      r.void.asJsonObject deepMerge r.value.asJsonObject
    }

  implicit final def resourceFUnitJsonLdEncoder(implicit
      base: BaseUri,
      mappings: ApiMappings = ApiMappings.empty
  ): JsonLdEncoder[ResourceF[Unit]] =
    JsonLdEncoder.compactFromCirce((v: ResourceF[Unit]) => v.id, iriContext = contexts.resource)

  implicit def resourceFAJsonLdEncoder[A: JsonLdEncoder](implicit
      base: BaseUri,
      mappings: ApiMappings = ApiMappings.empty
  ): JsonLdEncoder[ResourceF[A]] =
    JsonLdEncoder.compose(rf => (rf.void, rf.value, rf.id))
}
