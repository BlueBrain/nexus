package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.Functor
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import monix.bio.IO

import java.time.Instant

/**
  * A resource representation.
  *
  * @param id         the resource id
  * @param uris       the resource uris
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
    uris: ResourceUris,
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

  implicit final private def resourceFUnitEncoder(implicit base: BaseUri): Encoder.AsObject[ResourceF[Unit]] =
    Encoder.AsObject.instance { r =>
      JsonObject.empty
        .add(keywords.id, r.id.resolvedAgainst(base.endpoint.toIri).asJson)
        .add("_rev", r.rev.asJson)
        .add("_deprecated", r.deprecated.asJson)
        .add("_createdAt", r.createdAt.asJson)
        .add("_createdBy", r.createdBy.id.asJson)
        .add("_updatedAt", r.updatedAt.asJson)
        .add("_updatedBy", r.updatedBy.id.asJson)
        .add("_constrainedBy", r.schema.iri.asJson)
        .add("_self", r.uris.accessUriShortForm.asJson)
        .addIfExists("_project", r.uris.project)
        .addIfExists("_incoming", r.uris.incomingShortForm)
        .addIfExists("_outgoing", r.uris.outgoingShortForm)
        .addIfNonEmpty(keywords.tpe, r.types)
    }

  implicit def resourceFAEncoder[A: Encoder.AsObject](implicit base: BaseUri): Encoder.AsObject[ResourceF[A]] =
    Encoder.AsObject.instance { r =>
      r.void.asJsonObject deepMerge r.value.asJsonObject
    }

  private def resourceFUnitJsonLdEncoder(
      context: ContextValue
  )(implicit base: BaseUri): JsonLdEncoder[ResourceF[Unit]] =
    JsonLdEncoder.computeFromCirce(
      _.id.resolvedAgainst(base.endpoint.toIri),
      context
    )

  implicit final def resourceFUnitJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourceF[Unit]] =
    resourceFUnitJsonLdEncoder(ContextValue(contexts.metadata))

  implicit def resourceFAJsonLdEncoder[A](implicit
      base: BaseUri,
      A: JsonLdEncoder[A]
  ): JsonLdEncoder[ResourceF[A]] =
    new JsonLdEncoder[ResourceF[A]] {

      override def context(value: ResourceF[A]): ContextValue =
        A.context(value.value).merge(ContextValue(contexts.metadata))

      override def compact(
          value: ResourceF[A]
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] = {
        val metadataEncoder = resourceFUnitJsonLdEncoder(context(value))
        (A.compact(value.value), metadataEncoder.compact(value.void)).mapN {
          case (compactedA, compactedMetadata) =>
            compactedA.merge(compactedMetadata.rootId, compactedMetadata)
        }
      }

      override def expand(
          value: ResourceF[A]
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] = {
        val metadataEncoder = resourceFUnitJsonLdEncoder(context(value))
        (A.expand(value.value), metadataEncoder.expand(value.void)).mapN { case (expandedA, expandedMetadata) =>
          val rootId = expandedMetadata.rootId
          expandedA.replaceId(rootId).merge(rootId, expandedMetadata.replaceId(rootId))
        }
      }
    }
}
