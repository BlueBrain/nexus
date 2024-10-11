package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectLastUpdateStream
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import fs2.Pipe
import fs2.Stream
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Return signals indicating activity signals in the different projects
  */
trait ProjectActivitySignals {

  /**
    * Return the activity signal for the given project
    */
  def apply(project: ProjectRef): IO[Option[SignallingRef[IO, Boolean]]]

  /**
    * Returns a snapshot of the activity status of the different projects
    */
  def activityMap: IO[Map[ProjectRef, Boolean]]

}

object ProjectActivitySignals {

  val noop: ProjectActivitySignals = new ProjectActivitySignals {
    override def apply(project: ProjectRef): IO[Option[SignallingRef[IO, Boolean]]] = IO.none

    override def activityMap: IO[Map[ProjectRef, Boolean]] = IO.pure(Map.empty)
  }

  private def refresh(
      signals: ProjectSignals[ProjectLastUpdate],
      clock: Clock[IO],
      inactiveInterval: FiniteDuration,
      updated: Map[ProjectRef, ProjectLastUpdate]
  ) =
    clock.realTimeInstant.flatMap { now =>
      val inactivityThreshold = now.minusSeconds(inactiveInterval.toSeconds)
      signals.refresh(updated, _.lastInstant.isAfter(inactivityThreshold))
    }

  private def refreshStream(
      signals: ProjectSignals[ProjectLastUpdate],
      clock: Clock[IO],
      inactiveInterval: FiniteDuration
  ) =
    Stream.awakeEvery[IO](1.second).evalTap { _ =>
      refresh(signals, clock, inactiveInterval, Map.empty)
    }

  private[stream] def signalPipe(
      signals: ProjectSignals[ProjectLastUpdate],
      clock: Clock[IO],
      inactiveInterval: FiniteDuration
  ): Pipe[IO, ProjectLastUpdate, ProjectLastUpdate] =
    _.groupWithin(10, 100.millis)
      .evalTap { chunk =>
        val map = chunk.foldLeft(Map.empty[ProjectRef, ProjectLastUpdate]) { case (acc, update) =>
          acc.updated(update.project, update)
        }
        refresh(signals, clock, inactiveInterval, map)
      }
      .unchunks
      .concurrently(refreshStream(signals, clock, inactiveInterval))

  // $COVERAGE-OFF$
  private val projectionMetadata: ProjectionMetadata =
    ProjectionMetadata("system", "project-activity-signals", None, None)

  private val entityType: EntityType = EntityType("project-activity-signals")

  private def lastUpdatesId(project: ProjectRef): Iri = nxv + s"projection/project-activity-signals/$project"

  private def successElem(lastUpdate: ProjectLastUpdate): SuccessElem[Unit] =
    SuccessElem(
      entityType,
      lastUpdatesId(lastUpdate.project),
      lastUpdate.project,
      lastUpdate.lastInstant,
      lastUpdate.lastOrdering,
      (),
      1
    )

  def apply(
      supervisor: Supervisor,
      stream: ProjectLastUpdateStream,
      clock: Clock[IO],
      inactiveInterval: FiniteDuration
  ): IO[ProjectActivitySignals] = {

    for {
      signals <- ProjectSignals[ProjectLastUpdate]
      compiled =
        CompiledProjection.fromStream(
          projectionMetadata,
          ExecutionStrategy.EveryNode,
          (offset: Offset) =>
            stream(offset)
              .through(signalPipe(signals, clock, inactiveInterval))
              .map(successElem)
        )
      _       <- supervisor.run(compiled)
    } yield apply(signals)
  }
  // $COVERAGE-ON$

  def apply(signals: ProjectSignals[ProjectLastUpdate]): ProjectActivitySignals =
    new ProjectActivitySignals {
      override def apply(project: ProjectRef): IO[Option[SignallingRef[IO, Boolean]]] = signals.get(project)

      /**
        * Returns a snapshot of the activity status of the different projects
        */
      override def activityMap: IO[Map[ProjectRef, Boolean]] = signals.activityMap
    }
}
