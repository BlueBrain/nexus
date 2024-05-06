package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.ship.EventProcessor.logger
import fs2.Stream
import io.circe.Decoder

/**
  * Process events for the defined resource type
  */
trait EventProcessor[Event <: ScopedEvent] {

  def resourceType: EntityType

  def decoder: Decoder[Event]

  def evaluate(event: Event): IO[ImportStatus]

  def evaluate(event: RowEvent): IO[ImportStatus] =
    IO.fromEither(decoder.decodeJson(event.value))
      .onError(err => logger.error(err)(s"Error while attempting to decode $resourceType at offset ${event.ordering}"))
      .flatMap(evaluate)
}

object EventProcessor {

  private val logger = Logger[EventProcessor.type]

  def run(eventStream: Stream[IO, RowEvent], processors: EventProcessor[_]*): IO[ImportReport] = {
    val processorsMap = processors.foldLeft(Map.empty[EntityType, EventProcessor[_]]) { (acc, processor) =>
      acc + (processor.resourceType -> processor)
    }
    eventStream
      .evalScan(ImportReport.start) { case (report, event) =>
        val processed = report.progress.foldLeft(0L) { case (acc, (_, stats)) => acc + stats.success + stats.dropped }
        processorsMap.get(event.`type`) match {
          case Some(processor) =>
            IO.whenA(processed % 1000 == 0)(logger.info(s"Current progress is: ${report.progress}")) >>
              processor
                .evaluate(event)
                .map { status =>
                  report + (event, status)
                }
                .onError { err =>
                  logger.error(err)(
                    s"Error while processing event with offset '${event.ordering.value}' with processor '${event.`type`}'."
                  )
                }
          case None            =>
            logger.warn(s"No processor is provided for '${event.`type`}', skipping...") >>
              IO.pure(report + (event, ImportStatus.Dropped))
        }
      }
      .compile
      .lastOrError
      .flatTap { report => logger.info(report.show) }
  }

}
