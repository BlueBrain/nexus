package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShift
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef, Tags}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

/**
  * A resource representation
  *
  * @param id
  *   the resource identifier
  * @param project
  *   the project where the resource belongs
  * @param tags
  *   the resource tags
  * @param schema
  *   the schema used to constrain the resource
  * @param source
  *   the representation of the resource as posted by the subject
  * @param compacted
  *   the compacted JSON-LD representation of the resource
  * @param expanded
  *   the expanded JSON-LD representation of the resource
  */
final case class Resource(
    id: Iri,
    project: ProjectRef,
    tags: Tags,
    schema: ResourceRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd
) {
  def metadata: Metadata = Metadata(tags.tags)
}

object Resource {

  final case class Metadata(tags: List[UserTag])

  implicit val resourceJsonLdEncoder: JsonLdEncoder[Resource] =
    new JsonLdEncoder[Resource] {

      override def compact(
          value: Resource
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] =
        IO.pure(value.compacted)

      override def expand(
          value: Resource
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] =
        IO.pure(value.expanded)

      override def context(value: Resource): ContextValue =
        value.source.topContextValueOrEmpty
    }

  implicit val fileMetadataEncoder: Encoder[Metadata] = { m =>
    Json.obj("_tags" -> m.tags.asJson)
  }

  implicit val fileMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))

  def toJsonLdContent(value: ResourceF[Resource]): JsonLdContent[Resource, Metadata] =
    JsonLdContent(value, value.value.source, Some(value.value.metadata))

  type Shift = ResourceShift[ResourceState, Resource, Metadata]

  def shift(resources: Resources)(implicit baseUri: BaseUri): Shift =
    ResourceShift.withMetadata[ResourceState, Resource, Metadata](
      Resources.entityType,
      (ref, project) => resources.fetch(IdSegmentRef(ref), project, None),
      state => state.toResource,
      toJsonLdContent
    )
}
