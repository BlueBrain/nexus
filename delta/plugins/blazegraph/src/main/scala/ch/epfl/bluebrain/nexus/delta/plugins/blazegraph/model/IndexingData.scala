package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph

/**
  * Blazegraph indexing data
  *
  * @param graph         the resource graph
  * @param metadataGraph the graph with the metadata value triples
  */
final case class IndexingData(graph: Graph, metadataGraph: Graph)
