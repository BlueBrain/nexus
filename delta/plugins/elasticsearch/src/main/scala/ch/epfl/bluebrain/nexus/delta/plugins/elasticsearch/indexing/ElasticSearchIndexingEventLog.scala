package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdfs, schema, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingEventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import io.circe.Json
import monix.bio.Task
import org.apache.jena.rdf.model.Property

import scala.concurrent.duration.FiniteDuration

object ElasticSearchIndexingEventLog {

  private val graphPredicates: Set[Property] = Set(skos.prefLabel, rdfs.label, schema.name).map(predicate)

  /**
    * ElasticSearch indexing data
    *
    * @param selectPredicatesGraph the graph with the predicates in ''graphPredicates''
    * @param metadataGraph         the graph with the metadata value triples
    * @param source                the original payload of the resource posted by the caller
    */
  final case class IndexingData(selectPredicatesGraph: Graph, metadataGraph: Graph, source: Json)

  type EventStateResolution = Event => Task[ResourceF[IndexingData]]

  type ElasticSearchIndexingEventLog = IndexingEventLog[ResourceF[IndexingData]]

  /**
    * An event log that reads all events from a [[fs2.Stream]] and transforms each event to its [[IndexingData]] representation
    * using the available ''exchanges'' in preparation for indexing into Elasticsearch
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      exchanges: Set[EventExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit
      projectionId: ProjectionId,
      rcr: RemoteContextResolution,
      baseUri: BaseUri
  ): IndexingEventLog[ResourceF[IndexingData]] =
    IndexingEventLog(eventLog, exchanges, batchMaxSize, batchMaxTimeout) {
      case EventExchangeValue(ReferenceExchangeValue(resource, source, encoder), metadata) =>
        val id = resource.resolvedId
        for {
          graph        <- encoder.graph(resource.value)
          rootGraph     = graph.replace(graph.rootNode, id)
          metaGraph    <- metadata.encoder.graph(metadata.value)
          rootMetaGraph = metaGraph.replace(metaGraph.rootNode, id)
          s             = source.removeAllKeys(keywords.context)
          fGraph        = rootGraph.filter { case (s, p, _) => s == subject(id) && graphPredicates.contains(p) }
          data          = resource.as(IndexingData(fGraph, rootMetaGraph, s))
        } yield data
    }
}
