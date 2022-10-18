package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, owl}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.sdk.GraphResourceEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json
import monix.bio.IO

/**
  * A schema representation
  *
  * @param id
  *   the schema identifier
  * @param project
  *   the project where the schema belongs
  * @param tags
  *   the schema tags
  * @param source
  *   the representation of the schema as posted by the subject
  * @param compacted
  *   the compacted JSON-LD representation of the schema
  * @param expanded
  *   the expanded JSON-LD representation of the schema with the imports resolutions applied
  */
@SuppressWarnings(Array("OptionGet"))
final case class Schema(
    id: Iri,
    project: ProjectRef,
    tags: Tags,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: NonEmptyList[ExpandedJsonLd]
) {

  /**
    * the shacl shapes of the schema
    */
  // It is fine to do it unsafely since we have already computed the graph on evaluation previously in order to validate the schema.
  @transient
  lazy val shapes: ShaclShapesGraph = ShaclShapesGraph(graph(_.contains(nxv.Schema)))

  /**
    * the Graph representation of the imports that are ontologies
    */
  @transient
  lazy val ontologies: Graph = graph(types => types.contains(owl.Ontology) && !types.contains(nxv.Schema))

  private def graph(filteredTypes: Set[Iri] => Boolean): Graph = {
    implicit val api: JsonLdApi = JsonLdJavaApi.lenient
    val filtered                = expanded.filter(expanded => expanded.cursor.getTypes.exists(filteredTypes))
    val triples                 = filtered.map(_.toGraph.toOption.get).foldLeft(Set.empty[Triple])((acc, g) => acc ++ g.triples)
    Graph.empty(id).add(triples)
  }

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
        IO.pure(ExpandedJsonLd.unsafe(value.expanded.head.rootId, value.expanded.head.obj))

      override def context(value: Schema): ContextValue =
        value.source.topContextValueOrEmpty.merge(ContextValue(contexts.shacl))
    }

  def graphResourceEncoder(implicit baseUri: BaseUri): GraphResourceEncoder[SchemaState, Schema, Nothing] =
    GraphResourceEncoder.apply[SchemaState, Schema](
      Schemas.entityType,
      (context, state) => state.toResource(context.apiMappings, context.base),
      value => JsonLdContent(value, value.value.source, None)
    )

}
