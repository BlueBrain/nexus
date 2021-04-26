package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}

/**
  * Blazegraph indexing data
  *
  * @param id             the resource id
  * @param deprecated     whether the resource is deprecated
  * @param schema         the resource schema
  * @param types          the resource types
  * @param graph          the resource graph
  * @param metadataGraph  the graph with the metadata value triples
  */
final case class IndexingData(
    id: Iri,
    deprecated: Boolean,
    schema: ResourceRef,
    types: Set[Iri],
    graph: Graph,
    metadataGraph: Graph
)

object IndexingData {

  def apply(resource: ResourceF[_], graph: Graph, metadataGraph: Graph): IndexingData =
    IndexingData(resource.id, resource.deprecated, resource.schema, resource.types, graph, metadataGraph)
}
