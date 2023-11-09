package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.Functor
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.OrderingFields
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.{EphemeralResourceInProjectUris, ResourceInProjectAndSchemaUris, ResourceInProjectUris, RootResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

import java.time.Instant

/**
  * A resource representation.
  *
  * @param id
  *   the resource id
  * @param uris
  *   the resource uris
  * @param rev
  *   the revision of the resource
  * @param types
  *   the collection of known types of this resource
  * @param deprecated
  *   whether the resource is deprecated of not
  * @param createdAt
  *   the instant when this resource was created
  * @param createdBy
  *   the subject that created this resource
  * @param updatedAt
  *   the last instant when this resource was updated
  * @param updatedBy
  *   the last subject that updated this resource
  * @param schema
  *   the schema reference that this resource conforms to
  * @param value
  *   the resource value
  * @tparam A
  *   the resource value type
  */
final case class ResourceF[A](
    id: Iri,
    uris: ResourceUris,
    rev: Int,
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
    * @param f
    *   the function to apply to transform the current resource value
    * @return
    *   a new resource with a value of type B
    */
  def map[B](f: A => B): ResourceF[B] =
    copy(value = f(value))

  /**
    * @return
    *   the [[Iri]] resulting from resolving the current ''id'' against the ''base''
    */
  def resolvedId(implicit base: BaseUri): Iri =
    id.resolvedAgainst(base.endpoint.toIri)

  /**
    * @return
    *   the [[Iri]] resulting from resolving the self against the ''base''
    */
  def self(implicit base: BaseUri): Iri = uris.accessUri.toIri
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
  final def sortBy[A](field: String)(implicit orderingValueFields: OrderingFields[A]): Option[Ordering[ResourceF[A]]] =
    field match {
      case "@id"            => Some(Ordering[Iri] on (_.id))
      case "_rev"           => Some(Ordering[Int] on (_.rev))
      case "_deprecated"    => Some(Ordering[Boolean] on (_.deprecated))
      case "_createdAt"     => Some(defaultSort)
      case "_createdBy"     => Some(IriEncoder.ordering[Subject] on (_.createdBy))
      case "_updatedAt"     => Some(Ordering[Instant] on (_.updatedAt))
      case "_updatedBy"     => Some(IriEncoder.ordering[Subject] on (_.updatedBy))
      case "_constrainedBy" => Some(Ordering[Iri] on (_.schema.original))
      case field            => orderingValueFields(field).map(ordering => ordering on (_.value))
    }

  final private case class ResourceMetadata(
      uris: ResourceUris,
      rev: Int,
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
        JsonObject("_self" -> uris.accessUri.asJson)
      case uris: ResourceInProjectUris          =>
        JsonObject(
          "_self"     -> uris.accessUri.asJson,
          "_project"  -> uris.project.asJson,
          "_incoming" -> uris.incoming.asJson,
          "_outgoing" -> uris.outgoing.asJson
        )
      case uris: EphemeralResourceInProjectUris =>
        JsonObject(
          "_self"    -> uris.accessUri.asJson,
          "_project" -> uris.project.asJson
        )
      case uris: ResourceInProjectAndSchemaUris =>
        JsonObject(
          "_self"          -> uris.accessUri.asJson,
          "_project"       -> uris.project.asJson,
          "_schemaProject" -> uris.schemaProject.asJson,
          "_incoming"      -> uris.incoming.asJson,
          "_outgoing"      -> uris.outgoing.asJson
        )
    }

  implicit final private def metadataEncoder(implicit base: BaseUri): Encoder.AsObject[ResourceMetadata] = {
    implicit val subjectEncoder: Encoder[Subject] = IriEncoder.jsonEncoder[Subject]
    Encoder.AsObject.instance { r =>
      JsonObject.empty
        .add("_rev", r.rev.asJson)
        .add("_deprecated", r.deprecated.asJson)
        .add("_createdAt", r.createdAt.asJson)
        .add("_createdBy", r.createdBy.asJson)
        .add("_updatedAt", r.updatedAt.asJson)
        .add("_updatedBy", r.updatedBy.asJson)
        .add("_constrainedBy", r.schema.iri.asJson)
        .deepMerge(r.uris.asJsonObject)
    }
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

  /**
    * It generates an encoder of [[ResourceF]] of ''A'' using the [[JsonLdEncoder]] of ''A'', the [[JsonLdEncoder]] of
    * [[ResourceIdAndTypes]] and the [[JsonLdEncoder]] of [[ResourceMetadata]]
    *
    * The merging is done as follows:
    *   1. compact/expand the [[ResourceIdAndTypes]] using the generated ''context'' 2. compact/expand the ''A'' 3.
    *      compact/expand the [[ResourceMetadata]] Merges the resulting Jsons in the provided order 1,2,3. The order
    *      here is important, since some fields might be overridden in certain cases (i.e. cases where ''A'' already
    *      provides ''@id'' and ''@types'')
    */
  private def resourceFAJsonLdEncoder[A](
      overriddenContext: (JsonLdEncoder[A], ResourceF[A]) => ContextValue
  )(implicit
      encoder: JsonLdEncoder[A],
      base: BaseUri
  ): JsonLdEncoder[ResourceF[A]] =
    new JsonLdEncoder[ResourceF[A]] {

      // This context is used to compute the compacted/expanded form if the id and types json
      override def context(value: ResourceF[A]): ContextValue =
        // Remote context exclusion must be done to enforce immutability, since remote contexts can be updated.
        overriddenContext(encoder, value).excludeRemoteContexts

      override def compact(
          value: ResourceF[A]
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] = {
        implicit val idAndTypesEnc: JsonLdEncoder[ResourceIdAndTypes] = idAndTypesJsonLdEncoder(context(value))
        for {
          idAndTypes <- ResourceIdAndTypes(value.resolvedId, value.types).toCompactedJsonLd
          a          <- value.value.toCompactedJsonLd
          metadata   <- ResourceMetadata(value).toCompactedJsonLd
          rootId      = idAndTypes.rootId
        } yield idAndTypes.merge(rootId, a).merge(rootId, metadata)
      }

      override def expand(
          value: ResourceF[A]
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] = {
        implicit val idAndTypesEnc: JsonLdEncoder[ResourceIdAndTypes] = idAndTypesJsonLdEncoder(context(value))

        for {
          idAndTypes <- ResourceIdAndTypes(value.resolvedId, value.types).toExpandedJsonLd
          a          <- value.value.toExpandedJsonLd
          metadata   <- ResourceMetadata(value).toExpandedJsonLd
          rootId      = idAndTypes.rootId
        } yield idAndTypes.merge(rootId, a.replaceId(rootId)).merge(rootId, metadata.replaceId(rootId))
      }
    }
}
