package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.ProgressStatistics
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Statistics for a collection of composite view projections
  *
  * @param summary     the summary of statistics from the values
  * @param projections the collection of statistics
  */
final case class CompositeViewStatistics private (summary: ProgressStatistics, projections: List[ProjectionStatistics])

object CompositeViewStatistics {

  private def maxOption[A: Ordering](x: Option[A], y: Option[A]): Option[A] = (x ++ y).maxOption

  /**
    * Constructor helper from a collection of [[CompositeViewStatistics]]
    */
  // TODO: It does not make any sense to just take maximums/minimums and the resulting summary it is just misleading.
  // Summary should be removed from the API response and statistics endpoint should return a SearchResults[CompositeStatistics]
  def apply(values: List[ProjectionStatistics]): CompositeViewStatistics = {
    val (head, tail) =
      values.headOption.fold(ProgressStatistics.empty -> List.empty[ProjectionStatistics])(_.value -> values.tail)
    val summary      = tail.foldLeft(head) { case (acc, ProjectionStatistics(_, _, stats)) =>
      ProgressStatistics(
        processedEvents = stats.processedEvents.max(acc.processedEvents),
        discardedEvents = stats.discardedEvents.max(acc.discardedEvents),
        failedEvents = stats.failedEvents.max(acc.failedEvents),
        evaluatedEvents = stats.evaluatedEvents.max(acc.evaluatedEvents),
        remainingEvents = stats.remainingEvents.max(acc.remainingEvents),
        totalEvents = stats.totalEvents.max(acc.totalEvents),
        lastEventDateTime = maxOption(stats.lastEventDateTime, acc.lastEventDateTime),
        lastProcessedEventDateTime = maxOption(stats.lastProcessedEventDateTime, acc.lastProcessedEventDateTime),
        delayInSeconds = maxOption(stats.delayInSeconds, acc.delayInSeconds)
      )
    }
    CompositeViewStatistics(summary, values)
  }

  implicit private val compositeStatsCollectionEncoder: Encoder.AsObject[CompositeViewStatistics] =
    Encoder.encodeJsonObject.contramapObject { case CompositeViewStatistics(summary, values) =>
      summary.asJsonObject deepMerge JsonObject("values" -> values.sorted.asJson)
    }

  implicit val compositeStatsCollectionJsonLdEncoder: JsonLdEncoder[CompositeViewStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))

}
