package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingEventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

object BlazegraphIndexingEventLog {

  type EventStateResolution       = Event => Task[ResourceF[ExpandedJsonLd]]
  type BlazegraphIndexingEventLog = IndexingEventLog[ResourceF[Graph]]

  /**
    * An event log that reads all events from a [[fs2.Stream]] and transforms each event to its [[Graph]] representation
    * using the available ''exchanges'' in preparation for indexing into Blazegraph
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      exchanges: Set[EventExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit
      projectionId: ProjectionId,
      rcr: RemoteContextResolution
  ): BlazegraphIndexingEventLog =
    IndexingEventLog(eventLog, exchanges, batchMaxSize, batchMaxTimeout) { case EventExchangeValue(value, _) =>
      value.encoder.graph(value.toResource.value).map(value.toResource.as)
    }

}
