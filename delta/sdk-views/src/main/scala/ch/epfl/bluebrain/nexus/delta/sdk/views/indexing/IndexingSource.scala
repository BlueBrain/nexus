package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Message
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Stream}
import monix.bio.Task
import retry.syntax.all._

import scala.concurrent.duration.FiniteDuration

/**
  * A Stream source generated by reading events in a streaming fashion and
  * transforming each event its resource in preparation for indexing
  */
trait IndexingSource {

  /**
    * Fetch the stream of events inside a project and transforms them to its resource representation.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    * @param tag     the optional tag. If present, the resource is fetched at the specified tag. Otherwise, the latest
    *                revision of the resource if fetched
    */
  def apply(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[EventExchangeValue[_, _]]]]
}

object IndexingSource {

  /**
    * A Stream source generated by reading events in a streaming fashion and
    * transforming each event its resource using the available [[EventExchange]] in preparation for indexing
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      exchanges: Set[EventExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration,
      retryConfig: RetryStrategyConfig
  ): IndexingSource =
    new IndexingSource {
      private lazy val exchangesList = exchanges.toList
      private val logger: Logger     = Logger[IndexingSource.type]
      private val retryStrategy      = RetryStrategy.retryOnNonFatal(retryConfig, logger, "indexing stream")

      override def apply(
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
              case exchange :: rest =>
                exchange
                  .toResource(event, tag)
                  .map(_.toRight(rest).map(Some.apply))
                  .absorb
                  .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
            }
          }

    }
}
