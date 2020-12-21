package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import monix.bio.IO

/**
  * A resource representation
  *
  * @param id        the resource identifier
  * @param project   the project where the resource belongs
  * @param tags      the resource tags
  * @param schema    the schema used to constrain the resource
  * @param source    the representation of the resource as posted by the subject
  * @param compacted the compacted JSON-LD representation of the resource
  * @param expanded  the expanded JSON-LD representation of the resource
  */
final case class Resource(
    id: Iri,
    project: ProjectRef,
    tags: Map[TagLabel, Long],
    schema: ResourceRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd
)

object Resource {

  implicit val resourceJsonLdEncoder: JsonLdEncoder[Resource] =
    new JsonLdEncoder[Resource] {

      override def compact(
          value: Resource
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        IO.pure(value.compacted)

      override def expand(
          value: Resource
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        IO.pure(value.expanded)

      override def context(value: Resource): ContextValue =
        value.source.topContextValueOrEmpty
    }
}
