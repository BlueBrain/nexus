package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchGlobalEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchangeCollection, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.{IO, Task}

import scala.concurrent.duration.FiniteDuration

class ElasticSearchGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    orgs: Organizations,
    eventExchanges: EventExchangeCollection,
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
      .resource(event => eventExchanges.findFor(event).flatTraverse(_.toState(event, tag))) { state =>
        (state.toGraph, state.toSource).mapN { case (graph, source) => graph.map(IndexingData(_, source.value)) }
      }
}

object ElasticSearchGlobalEventLog {

  /**
    * ElasticSearch indexing data
    */
  final case class IndexingData(metadataGraph: Graph, source: Json)

  type EventStateResolution = Event => Task[ResourceF[IndexingData]]

  /**
    * Create an instance of [[ElasticSearchGlobalEventLog]].
    *
    * @param eventLog        the underlying [[EventLog]]
    * @param projects        the projects operations bundle
    * @param orgs            the organizations operations bundle
    * @param eventExchanges  the collection of [[EventExchange]]s to fetch latest state
    * @param batchMaxSize    the maximum batching size. In this window, duplicated persistence ids are discarded
    * @param batchMaxTimeout the maximum batching duration. In this window, duplicated persistence ids are discarded
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      orgs: Organizations,
      eventExchanges: EventExchangeCollection,
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit projectionId: ProjectionId): ElasticSearchGlobalEventLog =
    new ElasticSearchGlobalEventLog(eventLog, projects, orgs, eventExchanges, batchMaxSize, batchMaxTimeout)
}
