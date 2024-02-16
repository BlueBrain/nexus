package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.model.InputEvent
import fs2.Stream
import io.circe.Decoder

/**
  * Process events for the defined resource type
  */
trait EventProcessor[Event <: ScopedEvent] {

  def resourceType: EntityType

  def decoder: Decoder[Event]

  def evaluate(event: Event): IO[Unit]

  def evaluate(event: InputEvent): IO[Unit] =
    IO.fromEither(decoder.decodeJson(event.value)).flatMap(evaluate)
}

object EventProcessor {

  private val logger = Logger[EventProcessor.type]

  def run(processors: List[EventProcessor[_]], eventStream: Stream[IO, InputEvent]): IO[Offset] = {
    val processorsMap = processors.foldLeft(Map.empty[EntityType, EventProcessor[_]]) { (acc, processor) =>
      acc + (processor.resourceType -> processor)
    }
    eventStream
      .evalTap { event =>
        processorsMap.get(event.`type`) match {
          case Some(processor) => processor.evaluate(event)
          case None            => logger.warn(s"No processor is provided for '${event.`type`}', skipping...")
        }
      }
      .scan((0, Offset.start)) { case ((count, _), event) => (count + 1, event.ordering) }
      .compile
      .lastOrError
      .flatMap { case (count, offset) =>
        logger.info(s"$count events were imported up to offset $offset").as(offset)
      }
  }

}
