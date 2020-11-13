package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Dot
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
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
    tags: Map[Label, Long],
    schema: ResourceRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd
)

object Resource {

  implicit val resourceJsonLdEncoder: JsonLdEncoder[Resource] =
    new JsonLdEncoder[Resource] {

      override def apply(value: Resource): IO[RdfError, JsonLd] =
        IO.pure(value.expanded)

      override def compact(value: Resource, context: ContextValue)(implicit
          options: JsonLdOptions,
          api: JsonLdApi,
          resolution: RemoteContextResolution
      ): IO[RdfError, CompactedJsonLd] =
        IO.pure(value.compacted)

      override def expand(value: Resource)(implicit
          options: JsonLdOptions,
          api: JsonLdApi,
          resolution: RemoteContextResolution
      ): IO[RdfError, ExpandedJsonLd] =
        IO.pure(value.expanded)

      override def dot(value: Resource, context: ContextValue)(implicit
          options: JsonLdOptions,
          api: JsonLdApi,
          resolution: RemoteContextResolution
      ): IO[RdfError, Dot] =
        super.dot(value, value.source.topContextValueOrEmpty)

      override val defaultContext: ContextValue = ContextValue.empty
    }
}
