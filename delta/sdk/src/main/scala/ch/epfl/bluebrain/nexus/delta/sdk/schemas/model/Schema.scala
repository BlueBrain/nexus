package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, owl}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
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
  def shapes: IO[Graph] = graph(_.contains(nxv.Schema))

  def ontologies: IO[Graph] = graph(types => types.contains(owl.Ontology) && !types.contains(nxv.Schema))

  private def graph(filteredTypes: Set[Iri] => Boolean): IO[Graph] = {
    implicit val api: JsonLdApi                         = JsonLdJavaApi.lenient
    val init: (Set[IriOrBNode], Vector[ExpandedJsonLd]) = (Set.empty[IriOrBNode], Vector.empty[ExpandedJsonLd])
    val (_, filtered)                                   = expanded.foldLeft(init) {
      case ((seen, acc), expanded)
          if !seen.contains(expanded.rootId) && expanded.cursor.getTypes.exists(filteredTypes) =>
        val updatedSeen = seen + expanded.rootId
        val updatedAcc  = acc :+ expanded
        updatedSeen -> updatedAcc
      case ((seen, acc), _) => seen -> acc
    }
    filtered
      .foldLeftM(Set.empty[Triple]) { (acc, expanded) =>
        expanded.toGraph.map { g => acc ++ g.triples }
      }
      .map { triples => Graph.empty(id).add(triples) }
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
