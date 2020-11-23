package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import monix.bio.IO

/**
  * A schema representation
  *
  * @param id        the schema identifier
  * @param project   the project where the schema belongs
  * @param tags      the schema tags
  * @param source    the representation of the schema as posted by the subject
  * @param compacted the compacted JSON-LD representation of the schema
  * @param expanded  the expanded JSON-LD representation of the schema
  * @param graph     the Graph  representation of the schema
  */
final case class Schema(
    id: Iri,
    project: ProjectRef,
    tags: Map[Label, Long],
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd,
    graph: Graph
)

object Schema {

  implicit val schemaJsonLdEncoder: JsonLdEncoder[Schema] =
    new JsonLdEncoder[Schema] {

      override def compact(value: Schema): IO[RdfError, CompactedJsonLd] =
        IO.pure(value.compacted)

      override def expand(value: Schema)(implicit
          options: JsonLdOptions,
          api: JsonLdApi,
          resolution: RemoteContextResolution
      ): IO[RdfError, ExpandedJsonLd] =
        IO.pure(value.expanded)

      override def context(value: Schema): ContextValue =
        value.source.topContextValueOrEmpty.merge(ContextValue(contexts.shacl))
    }
}
