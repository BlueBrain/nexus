package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, owl}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import monix.bio.IO

/**
  * A schema representation
  *
  * @param id         the schema identifier
  * @param project    the project where the schema belongs
  * @param tags       the schema tags
  * @param source     the representation of the schema as posted by the subject
  * @param compacted  the compacted JSON-LD representation of the schema
  * @param expanded   the expanded JSON-LD representation of the schema with the imports resolutions applied
  */
@SuppressWarnings(Array("OptionGet"))
final case class Schema(
    id: Iri,
    project: ProjectRef,
    tags: Map[TagLabel, Long],
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd
) {

  /**
   * the shacl shapes of the schema
   */
  // It is fine to do it unsafely since we have already computed the graph on evaluation previously in order to validate the schema.
  @transient
  lazy val shapes: ShaclShapesGraph = ShaclShapesGraph(expanded.filterType(nxv.Schema).toGraph.toOption.get.model)

  /**
   * the Graph representation of the imports that are ontologies
   */
  @transient
  lazy val ontologies: Graph =
    expanded.filterTypes(types => types.contains(owl.Ontology) && !types.contains(nxv.Schema)).toGraph.toOption.get

}

object Schema {

  implicit val schemaJsonLdEncoder: JsonLdEncoder[Schema] =
    new JsonLdEncoder[Schema] {

      override def compact(
          value: Schema
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        IO.pure(value.compacted)

      override def expand(
          value: Schema
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        IO.pure(ExpandedJsonLd.unsafe(value.expanded.rootId, value.expanded.mainObj))

      override def context(value: Schema): ContextValue =
        value.source.topContextValueOrEmpty.merge(ContextValue(contexts.shacl))
    }
}
