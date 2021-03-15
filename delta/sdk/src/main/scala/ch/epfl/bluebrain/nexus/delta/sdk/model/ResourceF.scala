package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.Functor
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.{ResourceInProjectAndSchemaUris, ResourceInProjectUris, RootResourceUris}
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

  /**
    * @return the [[Iri]] resulting from resolving the current ''id'' against the ''base''
    */
  def resolvedId(implicit base: BaseUri): Iri =
    id.resolvedAgainst(base.endpoint.toIri)
}

object ResourceF {

  implicit val resourceFunctor: Functor[ResourceF] =
    new Functor[ResourceF] {
      override def map[A, B](fa: ResourceF[A])(f: A => B): ResourceF[B] = fa.map(f)
    }

  /**
    * Creates a default ordering of ''ResourceF'' by creation date
    */
  final def defaultSort[A]: Ordering[ResourceF[A]] = Ordering[Instant] on (r => r.createdAt)

  /**
    * Creates an ordering of ''ResourceF'' by the passed field name
    */
  final def sortBy[A](field: String): Option[Ordering[ResourceF[A]]] =
    field match {
      case "@id"            => Some(Ordering[Iri] on (r => r.id))
      case "_rev"           => Some(Ordering[Long] on (r => r.rev))
      case "_deprecated"    => Some(Ordering[Boolean] on (r => r.deprecated))
      case "_createdAt"     => Some(defaultSort)
      case "_createdBy"     => Some(Ordering[Subject] on (r => r.createdBy))
      case "_updatedAt"     => Some(Ordering[Instant] on (r => r.updatedAt))
      case "_updatedBy"     => Some(Ordering[Subject] on (r => r.updatedBy))
      case "_constrainedBy" => Some(Ordering[Iri] on (r => r.schema.original))
      case _                => None
    }

  final private case class ResourceMetadata(
      uris: ResourceUris,
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject,
      schema: ResourceRef
  )

  private object ResourceMetadata {

    def apply(r: ResourceF[_]): ResourceMetadata =
      ResourceMetadata(r.uris, r.rev, r.deprecated, r.createdAt, r.createdBy, r.updatedAt, r.updatedBy, r.schema)
  }

  implicit private def resourceUrisEncoder(implicit base: BaseUri): Encoder.AsObject[ResourceUris] =
    Encoder.AsObject.instance {
      case uris: RootResourceUris               =>
        JsonObject("_self" -> uris.accessUriShortForm.asJson)
      case uris: ResourceInProjectUris          =>
        JsonObject(
          "_self"     -> uris.accessUriShortForm.asJson,
          "_project"  -> uris.project.asJson,
          "_incoming" -> uris.incomingShortForm.asJson,
          "_outgoing" -> uris.outgoingShortForm.asJson
        )
      case uris: ResourceInProjectAndSchemaUris =>
        JsonObject(
          "_self"          -> uris.accessUriShortForm.asJson,
          "_project"       -> uris.project.asJson,
          "_schemaProject" -> uris.schemaProject.asJson,
          "_incoming"      -> uris.incomingShortForm.asJson,
          "_outgoing"      -> uris.outgoingShortForm.asJson
        )
    }

  implicit final private def metadataEncoder(implicit base: BaseUri): Encoder.AsObject[ResourceMetadata] =
    Encoder.AsObject.instance { r =>
      JsonObject.empty
        .add("_rev", r.rev.asJson)
        .add("_deprecated", r.deprecated.asJson)
        .add("_createdAt", r.createdAt.asJson)
        .add("_createdBy", r.createdBy.id.asJson)
        .add("_updatedAt", r.updatedAt.asJson)
        .add("_updatedBy", r.updatedBy.id.asJson)
        .add("_constrainedBy", r.schema.iri.asJson)
        .deepMerge(r.uris.asJsonObject)
    }

  implicit private def metadataJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourceMetadata] =
    JsonLdEncoder.computeFromCirce(BNode.random, ContextValue(contexts.metadata))

  implicit def resourceFEncoder[A: Encoder.AsObject](implicit base: BaseUri): Encoder.AsObject[ResourceF[A]] =
    Encoder.encodeJsonObject.contramapObject { r =>
      ResourceIdAndTypes(r.resolvedId, r.types).asJsonObject deepMerge
        r.value.asJsonObject deepMerge
        ResourceMetadata(r).asJsonObject
    }

  final private case class ResourceIdAndTypes(resolvedId: Iri, types: Set[Iri])

  implicit private val idAndTypesEncoder: Encoder.AsObject[ResourceIdAndTypes] =
    Encoder.AsObject.instance { case ResourceIdAndTypes(id, types) =>
      JsonObject.empty.add(keywords.id, id.asJson).addIfNonEmpty(keywords.tpe, types)
    }

  private def idAndTypesJsonLdEncoder(context: ContextValue): JsonLdEncoder[ResourceIdAndTypes] =
    JsonLdEncoder.computeFromCirce(_.resolvedId, context)

  implicit def defaultResourceFAJsonLdEncoder[A: JsonLdEncoder](implicit base: BaseUri): JsonLdEncoder[ResourceF[A]] =
    resourceFAJsonLdEncoder((encoder, value) => encoder.context(value.value) merge ContextValue(contexts.metadata))

  /**
    * Creates a [[JsonLdEncoder]] of a [[ResourceF]] of ''A'' using the available [[JsonLdEncoder]] of ''A'' and the
    * fixed context ''ctx''
    */
  def resourceFAJsonLdEncoder[A: JsonLdEncoder](ctx: ContextValue)(implicit
      base: BaseUri
  ): JsonLdEncoder[ResourceF[A]] =
    resourceFAJsonLdEncoder((_, _) => ctx merge ContextValue(contexts.metadata))

  private def resourceFAJsonLdEncoder[A](
      overriddenContext: (JsonLdEncoder[A], ResourceF[A]) => ContextValue
  )(implicit
      encoder: JsonLdEncoder[A],
      base: BaseUri
  ): JsonLdEncoder[ResourceF[A]] =
    new JsonLdEncoder[ResourceF[A]] {

      override def context(value: ResourceF[A]): ContextValue = overriddenContext(encoder, value)

      override def compact(
          value: ResourceF[A]
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] = {
        implicit val idAndTypesEnc: JsonLdEncoder[ResourceIdAndTypes] = idAndTypesJsonLdEncoder(context(value))
        for {
          idAndTypes <- ResourceIdAndTypes(value.resolvedId, value.types).toCompactedJsonLd
          a          <- value.value.toCompactedJsonLd
          metadata   <- ResourceMetadata(value).toCompactedJsonLd
          rootId      = idAndTypes.rootId
          // ''idAndTypes'' result can be overridden by ''a'' result which can be overridden by ''metadata'' result
        } yield idAndTypes.merge(rootId, a).merge(rootId, metadata)
      }

      override def expand(
          value: ResourceF[A]
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] = {
        implicit val idAndTypesEnc: JsonLdEncoder[ResourceIdAndTypes] = idAndTypesJsonLdEncoder(context(value))

        for {
          idAndTypes <- ResourceIdAndTypes(value.resolvedId, value.types).toExpandedJsonLd
          a          <- value.value.toExpandedJsonLd
          metadata   <- ResourceMetadata(value).toExpandedJsonLd
          rootId      = idAndTypes.rootId
          // ''idAndTypes'' result can be overridden by ''a'' result which can be overridden by ''metadata'' result
        } yield idAndTypes.merge(rootId, a.replaceId(rootId)).merge(rootId, metadata.replaceId(rootId))
      }
    }
}
