package ch.epfl.bluebrain.nexus.delta.service.eventlog

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventResolver, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, ResourceF}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task}

final class GlobalEventLogImpl private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    eventStateResolvers: Set[EventResolver]
) extends GlobalEventLog {

  private val logger: Logger = Logger[GlobalEventLogImpl]

  lazy val eventResolvers: Map[String, EventResolver] = eventStateResolvers.map(res => res.eventType -> res).toMap

  override def events(offset: Offset): Stream[Task, Envelope[Event]] = eventLog.eventsByTag(Event.eventTag, offset)

  override def events(project: ProjectRef, offset: Offset): IO[ProjectNotFound, Stream[Task, Envelope[Event]]] =
    projects.fetch(project).map(_ => eventLog.eventsByTag(Projects.projectTag(project), offset))

  override def latestStateAsExpandedJsonLd(event: Event): Task[Option[ResourceF[ExpandedJsonLd]]] =
    eventResolvers.get(event.eventType) match {
      case Some(res) => res.latestStateAsExpandedJsonLd(event)
      case None      =>
        logger.warn(s"No resolver found for event of type '${event.eventType}'.")
        Task.pure(None)
    }
}

object GlobalEventLogImpl {

  type EventStateResolution = Event => Task[ResourceF[ExpandedJsonLd]]

  /**
    * Create an instance of [[GlobalEventLogImpl]].
    *
    * @param eventLog             the underlying [[EventLog]]
    * @param projects             the projects operations bundle
    * @param eventStateResolvers  the list of resolvers to resolve latest state from an [[Event]]
    * @return
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      eventStateResolvers: Set[EventResolver]
  ): GlobalEventLogImpl =
    new GlobalEventLogImpl(eventLog, projects, eventStateResolvers)

}
