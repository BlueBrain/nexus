package ch.epfl.bluebrain.nexus.delta.service.eventlog

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchangeCollection, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, ResourceF}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task}

/**
  * An implementation of [[GlobalEventLog]] for [[ExpandedJsonLd]]
  */
final class ExpandedGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    eventExchanges: EventExchangeCollection
) extends GlobalEventLog[ResourceF[ExpandedJsonLd]] {

  private val logger: Logger = Logger[ExpandedGlobalEventLog]

  override def events(offset: Offset): Stream[Task, ResourceF[ExpandedJsonLd]] = exchange(
    eventLog.eventsByTag(Event.eventTag, offset)
  )

  override def events(
      project: ProjectRef,
      offset: Offset
  ): IO[ProjectNotFound, Stream[Task, ResourceF[ExpandedJsonLd]]] =
    projects.fetch(project).map(_ => exchange(eventLog.eventsByTag(Projects.projectTag(project), offset)))

  private def exchange(stream: Stream[Task, Envelope[Event]]): Stream[Task, ResourceF[ExpandedJsonLd]] = stream
    .flatMap { env =>
      eventExchanges.findFor(env.event) match {
        case Some(ex) => Stream.evalSeq(ex.toExpanded(env.event).map(_.toSeq))
        case None     =>
          logger.warn(s"Not exchange found for Event of type '${env.event.getClass.getName}'.")
          Stream.empty
      }
    }
}

object ExpandedGlobalEventLog {

  type EventStateResolution = Event => Task[ResourceF[ExpandedJsonLd]]

  /**
    * Create an instance of [[ExpandedGlobalEventLog]].
    *
    * @param eventLog             the underlying [[EventLog]]
    * @param projects             the projects operations bundle
    * @param eventExchanges       the collection of [[EventExchange]]s to fetch latest state
    * @return
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      eventExchanges: EventExchangeCollection
  ): ExpandedGlobalEventLog =
    new ExpandedGlobalEventLog(eventLog, projects, eventExchanges: EventExchangeCollection)

}
