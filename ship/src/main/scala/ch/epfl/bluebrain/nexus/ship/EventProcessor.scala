package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.ship.EventProcessor.logger
import fs2.Stream
import io.circe.Decoder
import io.circe.optics.JsonPath.root

/**
  * Process events for the defined resource type
  */
trait EventProcessor[Event <: ScopedEvent] {

  def resourceType: EntityType

  def decoder: Decoder[Event]

  def evaluate(event: Event): IO[ImportStatus]

  def evaluate(event: RowEvent)(implicit iriPatcher: IriPatcher): IO[ImportStatus] = {
    val value                            = event.value
    def patchEventId(idAsString: String) = Iri(idAsString).map(iriPatcher(_).toString).getOrElse(idAsString)
    val patchedValue                     = root.id.string.modify { patchEventId }(value)
    IO.fromEither(decoder.decodeJson(patchedValue))
      .onError(err => logger.error(err)(s"Error while attempting to decode $resourceType at offset ${event.ordering}"))
      .flatMap(evaluate)
  }
}

object EventProcessor {

  private val logger = Logger[EventProcessor.type]

  def run(
      eventStream: Stream[IO, RowEvent],
      droppedEventStore: DroppedEventStore,
      processors: EventProcessor[_]*
  )(implicit iriPatcher: IriPatcher): IO[ImportReport] = {
    val processorsMap = processors.foldLeft(Map.empty[EntityType, EventProcessor[_]]) { (acc, processor) =>
      acc + (processor.resourceType -> processor)
    }
    // Truncating dropped events from previous run before running the stream
    droppedEventStore.truncate >>
      eventStream
        .evalScan(ImportReport.start) { case (report, event) =>
          val processed = report.progress.foldLeft(0L) { case (acc, (_, stats)) => acc + stats.success + stats.dropped }
          processorsMap.get(event.`type`) match {
            case Some(processor) =>
              for {
                _      <- IO.whenA(processed % 1000 == 0)(logger.info(s"Current progress is: ${report.progress}"))
                status <- processor.evaluate(event).onError { err =>
                            val message =
                              s"Error while processing event with offset '${event.ordering.value}' with processor '${event.`type`}'."
                            logger.error(err)(message)
                          }
                _      <- IO.whenA(status == ImportStatus.Dropped)(droppedEventStore.save(event))
              } yield report + (event, status)
            case None            =>
              logger.warn(s"No processor is provided for '${event.`type`}', skipping...") >>
                droppedEventStore.save(event) >>
                IO.pure(report + (event, ImportStatus.Dropped))
          }
        }
        .compile
        .lastOrError
        .flatTap { report => logger.info(report.show) }
  }

}
