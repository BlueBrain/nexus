package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, owl}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShift
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegmentRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

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

  def metadata: Metadata = Metadata(tags.tags)
}

object Schema {

  final case class Metadata(tags: List[UserTag])

  implicit val schemaJsonLdEncoder: JsonLdEncoder[Schema] =
    new JsonLdEncoder[Schema] {

      override def compact(
          value: Schema
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] =
        IO.pure(value.compacted)

      override def expand(
          value: Schema
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] =
        IO.pure(ExpandedJsonLd.unsafe(value.expanded.head.rootId, value.expanded.head.obj))

      override def context(value: Schema): ContextValue =
        value.source.topContextValueOrEmpty.merge(ContextValue(contexts.shacl))
    }

  implicit private val fileMetadataEncoder: Encoder[Metadata] = { m =>
    Json.obj("_tags" -> m.tags.asJson)
  }

  implicit val fileMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))

  type Shift = ResourceShift[SchemaState, Schema, Metadata]

  def shift(schemas: Schemas)(implicit baseUri: BaseUri): Shift =
    ResourceShift.withMetadata[SchemaState, Schema, Metadata](
      Schemas.entityType,
      (ref, project) => schemas.fetch(IdSegmentRef(ref), project),
      state => state.toResource,
      value => JsonLdContent(value, value.value.source, Some(value.value.metadata))
    )

}
