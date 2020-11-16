package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import akka.http.scaladsl.model.Uri
import cats.Functor
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF.fixedBase
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * A resource representation.
  *
  * @param id         the function to generate the resource id
  * @param accessUrl  the function to generate the resource access Url
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
    id: BaseUri => Iri,
    accessUrl: BaseUri => Uri,
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

  private[ResourceF] val fixedId        = id(fixedBase)
  private[ResourceF] val fixedAccessUrl = accessUrl(fixedBase)

  override def hashCode(): Int =
    (fixedId, fixedAccessUrl, rev, types, deprecated, createdAt, createdBy, updatedAt, updatedBy, schema, value).##

  // format: off
  override def equals(obj: Any): Boolean =
    obj match {
      case b @ ResourceF(_, _, `rev`, `types`, `deprecated`, `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, `schema`, `value`) =>
        fixedId == b.fixedId && fixedAccessUrl == b.fixedAccessUrl
      case _ =>
        false
    }
  // format: on

  override def toString: String =
    s"fixedId = '$fixedId', fixedAccessUrl = '$fixedAccessUrl', rev = '$rev', types = '$types', deprecated = '$deprecated', createdAt = '$createdAt', createdBy = '$createdBy', updatedAt = '$updatedAt', updatedBy = '$updatedBy', schema = '$schema', value = '$value'"

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
  private[ResourceF] val fixedBase: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit val resourceFunctor: Functor[ResourceF] =
    new Functor[ResourceF] {
      override def map[A, B](fa: ResourceF[A])(f: A => B): ResourceF[B] = fa.map(f)
    }

  implicit final private def resourceFUnitEncoder(implicit base: BaseUri): Encoder.AsObject[ResourceF[Unit]] =
    Encoder.AsObject.instance { r =>
      val obj = JsonObject.empty
        .add(keywords.id, r.id(base).asJson)
        .add("_rev", r.rev.asJson)
        .add("_deprecated", r.deprecated.asJson)
        .add("_createdAt", r.createdAt.asJson)
        .add("_createdBy", r.createdBy.id.asJson)
        .add("_updatedAt", r.updatedAt.asJson)
        .add("_updatedBy", r.updatedBy.id.asJson)
        .add("_constrainedBy", r.schema.iri.asJson)
        .add("_self", r.accessUrl(base).asJson)
      r.types.take(2).toList match {
        case Nil         => obj
        case head :: Nil => obj.add(keywords.tpe, head.stripPrefix(nxv.base).asJson)
        case _           => obj.add(keywords.tpe, r.types.map(_.stripPrefix(nxv.base)).asJson)
      }
    }

  implicit def resourceFAEncoder[A: Encoder.AsObject](implicit base: BaseUri): Encoder.AsObject[ResourceF[A]] =
    Encoder.AsObject.instance { r =>
      r.void.asJsonObject deepMerge r.value.asJsonObject
    }

  implicit final def resourceFUnitJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourceF[Unit]] =
    JsonLdEncoder.fromCirce((v: ResourceF[Unit]) => v.id(base), iriContext = contexts.resource)

  implicit def resourceFAJsonLdEncoder[A: JsonLdEncoder](implicit base: BaseUri): JsonLdEncoder[ResourceF[A]] =
    JsonLdEncoder.compose(rf => (rf.value, rf.void, rf.id(base)))
}
