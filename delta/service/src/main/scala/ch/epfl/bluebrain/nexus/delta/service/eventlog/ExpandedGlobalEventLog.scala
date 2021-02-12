package ch.epfl.bluebrain.nexus.delta.service.eventlog

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
import ch.epfl.bluebrain.nexus.sourcing.projections.Message
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task}

/**
  * An implementation of [[GlobalEventLog]] for [[ExpandedJsonLd]]
  */
final class ExpandedGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    orgs: Organizations,
    eventExchanges: EventExchangeCollection
) extends GlobalEventLog[ResourceF[ExpandedJsonLd]] {

  private val logger: Logger = Logger[ExpandedGlobalEventLog]

  override def stream(offset: Offset, tag: Option[TagLabel] = None): Stream[Task, Message[ResourceF[ExpandedJsonLd]]] =
    exchange(
      eventLog.eventsByTag(Event.eventTag, offset),
      tag
    )

  override def projectStream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel] = None
  ): IO[ProjectNotFound, Stream[Task, Message[ResourceF[ExpandedJsonLd]]]] =
    projects.fetch(project).as(exchange(eventLog.eventsByTag(Projects.projectTag(project), offset), tag))

  override def orgStream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel] = None
  ): IO[OrganizationNotFound, Stream[Task, Message[ResourceF[ExpandedJsonLd]]]] =
    orgs.fetch(org).as(exchange(eventLog.eventsByTag(Organizations.orgTag(org), offset), tag))

  private def exchange(
      stream: Stream[Task, Envelope[Event]],
      tag: Option[TagLabel]
  ): Stream[Task, Message[ResourceF[ExpandedJsonLd]]] = stream
    .map(_.toMessage)
    .evalMap{ msg =>
      eventExchanges.findFor(msg.value) match {
        case Some(ex) =>
          logger.warn(s"Not exchange found for Event of type '${msg.value.getClass.getName}'.")
          ex.toExpanded(msg.value, tag).map(_.map(msg.as).getOrElse(msg.discarded))
        case None     => Task.delay(msg.discarded)
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
    * @param orgs                 the organizations operations bundle
    * @param eventExchanges       the collection of [[EventExchange]]s to fetch latest state
    * @return
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      orgs: Organizations,
      eventExchanges: EventExchangeCollection
  ): ExpandedGlobalEventLog =
    new ExpandedGlobalEventLog(eventLog, projects, orgs, eventExchanges: EventExchangeCollection)

}
