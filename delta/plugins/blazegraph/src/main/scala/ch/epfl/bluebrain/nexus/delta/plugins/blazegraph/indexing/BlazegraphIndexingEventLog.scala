package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Offset
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

/**
  * An event log that reads events from a [[Stream]] and transforms each event to its [[Graph]] representation
  * in preparation for indexing into Blazegraph
  */
trait BlazegraphIndexingEventLog {

  /**
    * Get stream of events inside a project and transforms them to its [[Graph]] representation.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    */
  def stream(project: ProjectRef, offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[ResourceF[Graph]]]]
}

object BlazegraphIndexingEventLog {

  type EventStateResolution = Event => Task[ResourceF[ExpandedJsonLd]]

  /**
    * An event log that reads all events from a [[Stream]] and transforms each event to its [[Graph]] representation
    * using the available ''exchanges'' in preparation for indexing into Blazegraph
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      exchanges: Set[EventExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit projectionId: ProjectionId, rcr: RemoteContextResolution): BlazegraphIndexingEventLog =
    new BlazegraphIndexingEventLog {
      private lazy val exchangesList = exchanges.toList

      def stream(
          project: ProjectRef,
          offset: Offset,
          tag: Option[TagLabel]
      ): Stream[Task, Chunk[Message[ResourceF[Graph]]]] =
        eventLog
          .eventsByTag(Projects.projectTag(project), offset)
          .map(_.toMessage)
          .groupWithin(batchMaxSize, batchMaxTimeout)
          .discardDuplicates()
          .evalMapFilterValue { event =>
            Task
              .tailRecM(exchangesList) { // try all event exchanges one at a time until there's a result
                case Nil              => Task.pure(Right(None))
                case exchange :: rest => exchange.toResource(event, tag).map(_.toRight(rest).map(Some.apply))
              }
              .flatMap {
                case Some(EventExchangeValue(value, _)) =>
                  value.encoder.graph(value.toResource.value).map(g => Some(value.toResource.as(g)))
                case None                               =>
                  Task.pure(None)
              }
          }
    }

}
