package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.ship.EventProcessor.logger
import ch.epfl.bluebrain.nexus.ship.model.InputEvent
import fs2.Stream
import io.circe.Decoder

/**
  * Process events for the defined resource type
  */
trait EventProcessor[Event <: ScopedEvent] {

  def resourceType: EntityType

  def decoder: Decoder[Event]

  def evaluate(event: Event): IO[ImportStatus]

  def evaluate(event: InputEvent): IO[ImportStatus] =
    IO.fromEither(decoder.decodeJson(event.value))
      .onError(err => logger.error(err)(s"Error while attempting to decode $resourceType at offset ${event.ordering}"))
      .flatMap(evaluate)
}

object EventProcessor {

  private val logger = Logger[EventProcessor.type]

  def run(eventStream: Stream[IO, InputEvent], processors: EventProcessor[_]*): IO[ImportReport] = {
    val processorsMap = processors.foldLeft(Map.empty[EntityType, EventProcessor[_]]) { (acc, processor) =>
      acc + (processor.resourceType -> processor)
    }
    eventStream
      .evalScan(ImportReport.start) { case (report, event) =>
        processorsMap.get(event.`type`) match {
          case Some(processor) =>
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
