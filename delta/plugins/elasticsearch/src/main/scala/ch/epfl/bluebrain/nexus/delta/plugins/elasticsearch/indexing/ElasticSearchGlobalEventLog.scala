package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchGlobalEventLog.{graphPredicates, IndexingData}
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdfs, schema, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects, ReferenceExchange}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.{IO, Task}

import scala.concurrent.duration.FiniteDuration

class ElasticSearchGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    orgs: Organizations,
    referenceExchanges: Set[ReferenceExchange],
    batchMaxSize: Int,
    batchMaxTimeout: FiniteDuration
)(implicit projectionId: ProjectionId)
    extends GlobalEventLog[Message[ResourceF[IndexingData]]] {

  override def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[ResourceF[IndexingData]]]] =
    exchange(eventLog.eventsByTag(Event.eventTag, offset), tag)

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[ProjectRejection.ProjectNotFound, Stream[Task, Chunk[Message[ResourceF[IndexingData]]]]] =
    projects.fetch(project).as(exchange(eventLog.eventsByTag(Projects.projectTag(project), offset), tag))

  override def stream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[OrganizationRejection.OrganizationNotFound, Stream[Task, Chunk[Message[ResourceF[IndexingData]]]]] =
    orgs.fetch(org).as(exchange(eventLog.eventsByTag(Organizations.orgTag(org), offset), tag))

  private def exchange(
      stream: Stream[Task, Envelope[Event]],
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[ResourceF[IndexingData]]]] =
    stream
      .map(_.toMessage)
      .groupWithin(batchMaxSize, batchMaxTimeout)
      .discardDuplicates()
      .evalMapFilterValue { event =>
        Task
          .tailRecM(referenceExchanges.toList) { // try all reference exchanges one at a time until there's a result
            case Nil              => Task.pure(Right(None))
            case exchange :: rest => exchange(event, tag).map(_.toRight(rest).map(value => Some(value)))
          }
          .flatMap {
            case Some(value) =>
              for {
                graph    <- value.toGraph
                source    = value.toSource
                resourceF = value.toResource
                fGraph    = graph.filter { case (s, p, _) => s == subject(resourceF.id) && graphPredicates.contains(p) }
                data      = resourceF.map(_ => IndexingData(fGraph, source))
              } yield Some(data)
            case None        => Task.pure(None)
          }
      }
}

object ElasticSearchGlobalEventLog {

  private[indexing] val graphPredicates = Set(skos.prefLabel, rdfs.label, schema.name).map(predicate)

  /**
    * ElasticSearch indexing data
    *
    * @param selectPredicatesGraph the graph with the predicates in ''graphPredicates''
    * @param source                the original payload of the resource posted by the caller
    */
  final case class IndexingData(selectPredicatesGraph: Graph, source: Json)

  type EventStateResolution = Event => Task[ResourceF[IndexingData]]

  /**
    * Create an instance of [[ElasticSearchGlobalEventLog]].
    *
    * @param eventLog           the underlying [[EventLog]]
    * @param projects           the projects operations bundle
    * @param orgs               the organizations operations bundle
    * @param referenceExchanges the collection of [[ReferenceExchange]]s to fetch latest state
    * @param batchMaxSize       the maximum batching size. In this window, duplicated persistence ids are discarded
    * @param batchMaxTimeout    the maximum batching duration. In this window, duplicated persistence ids are discarded
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      orgs: Organizations,
      referenceExchanges: Set[ReferenceExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit projectionId: ProjectionId): ElasticSearchGlobalEventLog =
    new ElasticSearchGlobalEventLog(eventLog, projects, orgs, referenceExchanges, batchMaxSize, batchMaxTimeout)
}
