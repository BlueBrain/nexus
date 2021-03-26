package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

/**
  * An event log that reads events from a [[Stream]], transforms each event its latest resource and then converts it to ''A''
  * in preparation for indexing
  */
trait IndexingEventLog[A] {

  /**
    * Get stream of events inside a project and transforms them to its [[A]] representation.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    */
  def stream(project: ProjectRef, offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[A]]]
}

object IndexingEventLog {

  /**
    * An event log that reads all events from a [[Stream]], transforms each event to its latest resource [[EventExchangeValue]]
    * and then transforms it further to an ''A'' representation using the passed ''f'' function.
    * The events are transformed to its [[EventExchangeValue]] using the available ''exchanges''.
    */
  def apply[A](
      eventLog: EventLog[Envelope[Event]],
      exchanges: Set[EventExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(f: EventExchangeValue[_, _] => Task[A])(implicit projectionId: ProjectionId): IndexingEventLog[A] =
    new IndexingEventLog[A] {
      private val underlying = exchangeValue(eventLog, exchanges, batchMaxSize, batchMaxTimeout)

      override def stream(project: ProjectRef, offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[A]]] =
        underlying.stream(project, offset, tag).evalMapValue(f)
    }

  /**
    * An event log that reads all events from a [[Stream]] and transforms each event to its latest resource [[EventExchangeValue]]
    * representation using the available ''exchanges'' in preparation for indexing
    */
  def exchangeValue(
      eventLog: EventLog[Envelope[Event]],
      exchanges: Set[EventExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit projectionId: ProjectionId): IndexingEventLog[EventExchangeValue[_, _]] =
    new IndexingEventLog[EventExchangeValue[_, _]] {
      private lazy val exchangesList = exchanges.toList

      override def stream(
          project: ProjectRef,
          offset: Offset,
          tag: Option[TagLabel]
      ): Stream[Task, Chunk[Message[EventExchangeValue[_, _]]]] =
        eventLog
          .eventsByTag(Projects.projectTag(project), offset)
          .map(_.toMessage)
          .groupWithin(batchMaxSize, batchMaxTimeout)
          .discardDuplicates()
          .evalMapFilterValue { event =>
            Task.tailRecM(exchangesList) { // try all event exchanges one at a time until there's a result
              case Nil              => Task.pure(Right(None))
              case exchange :: rest => exchange.toResource(event, tag).map(_.toRight(rest).map(Some.apply))
            }
          }

    }
}
