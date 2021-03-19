package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.persistence.query.Offset
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdfs, schema, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

/**
  * An event log that reads events from a [[Stream]] and transforms each event to its [[IndexingData]] representation
  * in preparation for indexing into Elasticsearch
  */
trait ElasticSearchIndexingEventLog {

  /**
    * Get stream of events inside a project and transforms them to its [[IndexingData]] representation.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    */
  def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[ResourceF[IndexingData]]]]
}

object ElasticSearchIndexingEventLog {

  /**
    * ElasticSearch indexing data
    *
    * @param selectPredicatesGraph the graph with the predicates in ''graphPredicates''
    * @param metadataGraph         the graph with the metadata value triples
    * @param source                the original payload of the resource posted by the caller
    */
  final case class IndexingData(selectPredicatesGraph: Graph, metadataGraph: Graph, source: Json)

  type EventStateResolution = Event => Task[ResourceF[IndexingData]]

  /**
    * An event log that reads all events from a [[Stream]] and transforms each event to its [[IndexingData]] representation
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
  ): ElasticSearchIndexingEventLog =
    new ElasticSearchIndexingEventLog {
      private val graphPredicates    = Set(skos.prefLabel, rdfs.label, schema.name).map(predicate)
      private lazy val exchangesList = exchanges.toList

      override def stream(
          project: ProjectRef,
          offset: Offset,
          tag: Option[TagLabel]
      ): Stream[Task, Chunk[Message[ResourceF[IndexingData]]]] =
        eventLog
          .eventsByTag(Projects.projectTag(project), offset)
          .map(_.toMessage)
          .groupWithin(batchMaxSize, batchMaxTimeout)
          .discardDuplicates()
          .evalMapFilterValue { event =>
            Task
              .tailRecM(exchangesList) { // try all event exchanges one at a time until there's a result
                case Nil              => Task.pure(Right(None))
                case exchange :: rest => exchange.toResource(event, tag).map(_.toRight(rest).map(Some.apply))
              }
              .flatMap {
                case Some(EventExchangeValue(ReferenceExchangeValue(resource, source, encoder), metadata)) =>
                  val id = resource.resolvedId
                  for {
                    graph        <- encoder.graph(resource.value)
                    rootGraph     = graph.replace(graph.rootNode, id)
                    metaGraph    <- metadata.encoder.graph(metadata.value)
                    rootMetaGraph = metaGraph.replace(metaGraph.rootNode, id)
                    s             = source.removeAllKeys(keywords.context)
                    fGraph        = rootGraph.filter { case (s, p, _) => s == subject(id) && graphPredicates.contains(p) }
                    data          = resource.as(IndexingData(fGraph, rootMetaGraph, s))
                  } yield Some(data)
                case None                                                                                  => Task.pure(None)
              }
          }

    }
}
