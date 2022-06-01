package ch.epfl.bluebrain.nexus.delta.sdk

import akka.persistence.query.Offset
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import fs2.Stream
import monix.bio.{IO, Task}

/**
  * An event log that reads events from a [[Stream]] and transforms each event to JSON in preparation for consumption by
  * SSE routes
  */
trait SseEventLog {

  /**
    * Get stream of all events as ''T''.
    *
    * @param offset
    *   the offset to start from
    */
  def stream(offset: Offset): Stream[Task, Envelope[JsonValue.Aux[Event]]]

  /**
    * Get stream of events inside an organization as ''T'', transforming the error from ''OrganizationRejection'' to
    * ''R''.
    *
    * @param org
    *   the organization label
    * @param offset
    *   the offset to start from
    */
  def stream[R](
      org: Label,
      offset: Offset
  )(implicit mapper: Mapper[OrganizationRejection, R]): IO[R, Stream[Task, Envelope[JsonValue.Aux[Event]]]]

  /**
    * Get stream of events inside a project as ''T'', transforming the error from ''ProjectRejection'' to ''R''.
    *
    * @param project
    *   the project reference
    * @param offset
    *   the offset to start from
    */
  def stream[R](
      project: ProjectRef,
      offset: Offset
  )(implicit mapper: Mapper[ProjectRejection, R]): IO[R, Stream[Task, Envelope[JsonValue.Aux[Event]]]]
}

object SseEventLog {

  /**
    * An event log that reads events from a [[Stream]] and transforms each event to JSON-LD that is available through
    * ''exchanges''. The JSON-LD events are then used for consumption by SSE routes
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      orgs: Organizations,
      projects: Projects,
      exchanges: Set[EventExchange]
  ): SseEventLog =
    apply(eventLog, orgs, projects, exchanges, Event.eventTag)

  /**
    * An event log that reads events from a [[Stream]] filtered by tag ''tag'' and transforms each event to JSON-LD that
    * is available through ''exchanges''. The JSON-LD events are then used for consumption by SSE routes
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      orgs: Organizations,
      projects: Projects,
      exchanges: Set[EventExchange],
      tag: String
  ): SseEventLog = new SseEventLog {

    private lazy val exchangesList = exchanges.toList

    def stream(offset: Offset): Stream[Task, Envelope[JsonValue.Aux[Event]]] =
      exchange(eventLog.eventsByTag(tag, offset))

    def stream[R](
        org: Label,
        offset: Offset
    )(implicit mapper: Mapper[OrganizationRejection, R]): IO[R, Stream[Task, Envelope[JsonValue.Aux[Event]]]] =
      eventLog.orgEvents(orgs, org, offset).map(exchange)

    def stream[R](
        project: ProjectRef,
        offset: Offset
    )(implicit mapper: Mapper[ProjectRejection, R]): IO[R, Stream[Task, Envelope[JsonValue.Aux[Event]]]] =
      eventLog.projectEvents(projects, project, offset).map(exchange)

    private def exchange(stream: Stream[Task, Envelope[Event]]): Stream[Task, Envelope[JsonValue.Aux[Event]]] =
      stream
        .map { envelope =>
          exchangesList.tailRecM[Option, Envelope[JsonValue.Aux[Event]]] {
            case Nil              => None
            case exchange :: rest =>
              exchange.toJsonEvent(envelope.event) match {
                case Some(json) => Some(Right(envelope.as(json).asInstanceOf[Envelope[JsonValue.Aux[Event]]]))
                case None       => Some(Left(rest))
              }
          }
        }
        .collect { case Some(envelope) => envelope }
  }
}
