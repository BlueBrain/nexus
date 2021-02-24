package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import java.time.Instant

/**
  * Statistic for a projections' progress compared to the baseline (the project counts)
  *
  * @param processedEvents            count of processed events
  * @param discardedEvents            count of events dropped
  * @param failedEvents               count of events failed
  * @param evaluatedEvents            count of events in the stream that have been used to update an index
  * @param remainingEvents            count of events still remaining to be processed
  * @param totalEvents                total number of events for the project
  * @param lastEventDateTime          datetime of the last event in the project
  * @param lastProcessedEventDateTime time of the last processed event in the project
  * @param delayInSeconds             indexing delay
  */
final case class ProgressStatistics(
    processedEvents: Long,
    discardedEvents: Long,
    failedEvents: Long,
    evaluatedEvents: Long,
    remainingEvents: Long,
    totalEvents: Long,
    lastEventDateTime: Option[Instant],
    lastProcessedEventDateTime: Option[Instant],
    delayInSeconds: Option[Long]
)
object ProgressStatistics {

  /**
    * Empty progress statistics
    */
  val empty: ProgressStatistics = ProgressStatistics(0, 0, 0, 0, 0, 0, None, None, None)

  final def apply(
      processedEvents: Long,
      discardedEvents: Long,
      failedEvents: Long,
      totalEvents: Long,
      lastEventDateTime: Option[Instant],
      lastProcessedEventDateTime: Option[Instant]
  ): ProgressStatistics =
    new ProgressStatistics(
      processedEvents,
      discardedEvents,
      failedEvents,
      evaluatedEvents = processedEvents - discardedEvents - failedEvents,
      remainingEvents = totalEvents - processedEvents,
      totalEvents,
      lastEventDateTime,
      lastProcessedEventDateTime,
      (lastEventDateTime, lastProcessedEventDateTime).mapN { case (lastInstant, lastProcessedInstant) =>
        lastInstant.minusMillis(lastProcessedInstant.toEpochMilli).getEpochSecond
      }
    )

  implicit private val progressStatisticEncoder: Encoder.AsObject[ProgressStatistics] = deriveEncoder

  implicit val progressStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))

}
