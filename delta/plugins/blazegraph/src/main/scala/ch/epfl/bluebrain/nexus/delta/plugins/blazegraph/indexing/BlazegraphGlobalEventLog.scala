package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchangeCollection, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, ProjectionId}
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

/**
  * An implementation of [[GlobalEventLog]]
  */
final class BlazegraphGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    orgs: Organizations,
    eventExchanges: EventExchangeCollection
)(implicit projectionId: ProjectionId, sc: Scheduler)
    extends GlobalEventLog[Message[ResourceF[ExpandedJsonLd]]] {

  override def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Message[ResourceF[ExpandedJsonLd]]] =
    exchange(eventLog.eventsByTag(Event.eventTag, offset), tag)

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[ProjectNotFound, Stream[Task, Message[ResourceF[ExpandedJsonLd]]]] =
    projects.fetch(project).as(exchange(eventLog.eventsByTag(Projects.projectTag(project), offset), tag))

  override def stream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[OrganizationNotFound, Stream[Task, Message[ResourceF[ExpandedJsonLd]]]] =
    orgs.fetch(org).as(exchange(eventLog.eventsByTag(Organizations.orgTag(org), offset), tag))

  private def exchange(
      stream: Stream[Task, Envelope[Event]],
      tag: Option[TagLabel]
  ): Stream[Task, Message[ResourceF[ExpandedJsonLd]]] =
    stream
      .map(_.toMessage)
      .resource(event => eventExchanges.findFor(event).flatTraverse(_.toState(event, tag)))(_.toExpanded)
}

object BlazegraphGlobalEventLog {

  type EventStateResolution = Event => Task[ResourceF[ExpandedJsonLd]]

  /**
    * Create an instance of [[BlazegraphGlobalEventLog]].
    *
    * @param eventLog             the underlying [[EventLog]]
    * @param projects             the projects operations bundle
    * @param orgs                 the organizations operations bundle
    * @param eventExchanges       the collection of [[EventExchange]]s to fetch latest state
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      orgs: Organizations,
      eventExchanges: EventExchangeCollection
  )(implicit projectionId: ProjectionId, sc: Scheduler): BlazegraphGlobalEventLog =
    new BlazegraphGlobalEventLog(eventLog, projects, orgs, eventExchanges)

}
