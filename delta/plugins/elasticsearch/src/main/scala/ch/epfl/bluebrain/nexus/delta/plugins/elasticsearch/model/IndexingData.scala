package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdfs, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import io.circe.Json
import org.apache.jena.rdf.model.Property

/**
  * ElasticSearch indexing data
  *
  * @param selectPredicatesGraph the graph with the predicates in ''graphPredicates''
  * @param metadataGraph         the graph with the metadata value triples
  * @param source                the original payload of the resource posted by the caller
  */
final case class IndexingData(selectPredicatesGraph: Graph, metadataGraph: Graph, source: Json)

object IndexingData {
  val graphPredicates: Set[Property] = Set(skos.prefLabel, rdfs.label, Vocabulary.schema.name).map(predicate)
}
