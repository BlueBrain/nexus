package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdfs, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceRef}
import io.circe.Json
import org.apache.jena.graph.Node

/**
  * ElasticSearch indexing data
  *
  * @param id                    the resource id
  * @param deprecated            whether the resource is deprecated
  * @param schema                the resource schema
  * @param types                 the resource types
  * @param graph                 the graph with non-metadata predicates
  * @param metadataGraph         the graph with the metadata value triples
  * @param source                the original payload of the resource posted by the caller
  */
final case class IndexingData(
    id: Iri,
    deprecated: Boolean,
    schema: ResourceRef,
    types: Set[Iri],
    graph: Graph,
    metadataGraph: Graph,
    source: Json
)

object IndexingData {
  val graphPredicates: Set[Node] = Set(skos.prefLabel, rdfs.label, Vocabulary.schema.name).map(predicate)

  def apply(resource: ResourceF[_], graph: Graph, metadataGraph: Graph, source: Json)(implicit
      baseUri: BaseUri
  ): IndexingData =
    IndexingData(
      resource.resolvedId,
      resource.deprecated,
      resource.schema,
      resource.types,
      graph,
      metadataGraph,
      source
    )

  def apply(resource: ResourceF[_], graph: Graph, metadataGraph: Graph)(implicit
      baseUri: BaseUri
  ): IndexingData =
    IndexingData(
      resource.resolvedId,
      resource.deprecated,
      resource.schema,
      resource.types,
      graph,
      metadataGraph,
      Json.obj()
    )
}
