package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.Instant

import ch.epfl.bluebrain.nexus.kg.IdStats
import ch.epfl.bluebrain.nexus.kg.config.Contexts.statisticsCtxUri
import ch.epfl.bluebrain.nexus.rdf.implicits._

import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}

sealed trait Statistics extends Product with Serializable

object Statistics {

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  private def max[A: Ordering](left: Option[A], right: Option[A]): Option[A] =
    (left ++ right).toList match {
      case Nil  => None
      case list => Some(list.max)
    }

  /**
    * View statistics.
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
    * @param nextRestart                next time when a restart is going to be triggered
    */
  final case class ViewStatistics(
      processedEvents: Long,
      discardedEvents: Long,
      failedEvents: Long,
      evaluatedEvents: Long,
      remainingEvents: Long,
      totalEvents: Long,
      lastEventDateTime: Option[Instant],
      lastProcessedEventDateTime: Option[Instant],
      delayInSeconds: Option[Long],
      nextRestart: Option[Instant]
  ) extends Statistics

  object ViewStatistics {
    val empty: Statistics = ViewStatistics(0L, 0L, 0L, 0L, None, None)

    /**
      * Create an instance of [[Statistics]].
      */
    def apply(
        processedEvents: Long,
        discardedEvents: Long,
        failedEvents: Long,
        totalEvents: Long,
        lastEventDateTime: Option[Instant],
        lastProcessedEventDateTime: Option[Instant],
        nextRestart: Option[Instant] = None
    ): ViewStatistics =
      ViewStatistics(
        processedEvents = processedEvents,
        totalEvents = totalEvents,
        discardedEvents = discardedEvents,
        failedEvents = failedEvents,
        remainingEvents = totalEvents - processedEvents,
        evaluatedEvents = processedEvents - discardedEvents - failedEvents,
        lastEventDateTime = lastEventDateTime,
        lastProcessedEventDateTime = lastProcessedEventDateTime,
        nextRestart = nextRestart,
        delayInSeconds = for {
          lastEvent          <- lastEventDateTime
          lastProcessedEvent <- lastProcessedEventDateTime
        } yield lastEvent.getEpochSecond - lastProcessedEvent.getEpochSecond
      )
  }

  /**
    * Composite view statistics.
    *
    * @param processedEvents            sum of count of processed events
    * @param discardedEvents            sum of count of events dropped
    * @param failedEvents               sum of count of events failed
    * @param evaluatedEvents            sum of count of events in the stream that have been used to update an index
    * @param remainingEvents            sum of count of events still remaining to be processed
    * @param totalEvents                sum of total number of events for the project
    * @param lastEventDateTime          maximum datetime of the last event in the project
    * @param lastProcessedEventDateTime maximum time of the last processed event in the project
    * @param delayInSeconds             maximum indexing delay
    * @param nextRestart                maximum next time when a restart is going to be triggered
    * @param values                      detail of each statistics
    */
  final case class CompositeViewStatistics(
      processedEvents: Long,
      discardedEvents: Long,
      failedEvents: Long,
      evaluatedEvents: Long,
      remainingEvents: Long,
      totalEvents: Long,
      lastEventDateTime: Option[Instant],
      lastProcessedEventDateTime: Option[Instant],
      delayInSeconds: Option[Long],
      nextRestart: Option[Instant],
      values: Set[IdStats]
  ) extends Statistics { self =>
    def +(that: IdStats): Statistics =
      CompositeViewStatistics(
        self.processedEvents + that.value.processedEvents,
        self.discardedEvents + that.value.discardedEvents,
        self.failedEvents + that.value.failedEvents,
        self.evaluatedEvents + that.value.evaluatedEvents,
        self.remainingEvents + that.value.remainingEvents,
        self.totalEvents + that.value.totalEvents,
        max(self.lastEventDateTime, that.value.lastEventDateTime),
        max(self.lastProcessedEventDateTime, that.value.lastProcessedEventDateTime),
        max(self.delayInSeconds, that.value.delayInSeconds),
        max(self.nextRestart, that.value.nextRestart),
        values + that
      )

  }

  object CompositeViewStatistics {
    def apply(idStats: IdStats): CompositeViewStatistics =
      CompositeViewStatistics(
        idStats.value.processedEvents,
        idStats.value.discardedEvents,
        idStats.value.failedEvents,
        idStats.value.evaluatedEvents,
        idStats.value.remainingEvents,
        idStats.value.totalEvents,
        idStats.value.lastEventDateTime,
        idStats.value.lastProcessedEventDateTime,
        idStats.value.delayInSeconds,
        idStats.value.nextRestart,
        Set(idStats)
      )
  }

  @nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit val viewStatisticsEncoder: Encoder[ViewStatistics]               =
    deriveConfiguredEncoder[ViewStatistics]
      .mapJson(_ deepMerge Json.obj("@type" -> "ViewStatistics".asJson))

  implicit val compositeStatisticsEncoder: Encoder[CompositeViewStatistics] =
    deriveConfiguredEncoder[CompositeViewStatistics]
      .mapJson(_ deepMerge Json.obj("@type" -> "CompositeViewStatistics".asJson))

  implicit val statisticsEncoder: Encoder[Statistics]                       =
    Encoder.instance {
      case stat: ViewStatistics          => stat.asJson.addContext(statisticsCtxUri)
      case stat: CompositeViewStatistics => stat.asJson.addContext(statisticsCtxUri)
    }

}
