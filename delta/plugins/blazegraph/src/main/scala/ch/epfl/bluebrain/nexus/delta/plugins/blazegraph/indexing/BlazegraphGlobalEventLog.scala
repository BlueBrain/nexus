package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchangeCollection, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import monix.bio.{IO, Task}

import scala.concurrent.duration.FiniteDuration

/**
  * An implementation of [[GlobalEventLog]]
  */
final class BlazegraphGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    orgs: Organizations,
    eventExchanges: EventExchangeCollection,
    batchMaxSize: Int,
    batchMaxTimeout: FiniteDuration
)(implicit projectionId: ProjectionId)
    extends GlobalEventLog[Message[ResourceF[Graph]]] {

  override def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[ResourceF[Graph]]]] =
    exchange(eventLog.eventsByTag(Event.eventTag, offset), tag)

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[ProjectNotFound, Stream[Task, Chunk[Message[ResourceF[Graph]]]]] =
    projects.fetch(project).as(exchange(eventLog.eventsByTag(Projects.projectTag(project), offset), tag))

  override def stream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[OrganizationNotFound, Stream[Task, Chunk[Message[ResourceF[Graph]]]]] =
    orgs.fetch(org).as(exchange(eventLog.eventsByTag(Organizations.orgTag(org), offset), tag))

  private def exchange(
      stream: Stream[Task, Envelope[Event]],
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[ResourceF[Graph]]]] =
    stream
      .map(_.toMessage)
      .groupWithin(batchMaxSize, batchMaxTimeout)
      .discardDuplicates()
      .evalMapFilterValue(event => eventExchanges.findFor(event).traverse(_.toState(event, tag).flatMap(_.toGraph)))
}

object BlazegraphGlobalEventLog {

  type EventStateResolution = Event => Task[ResourceF[ExpandedJsonLd]]

  /**
    * Create an instance of [[BlazegraphGlobalEventLog]].
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
  )(implicit projectionId: ProjectionId): BlazegraphGlobalEventLog =
    new BlazegraphGlobalEventLog(eventLog, projects, orgs, eventExchanges, batchMaxSize, batchMaxTimeout)

}
