package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import cats.effect.std.AtomicCell
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectSignals.emptySignals
import fs2.concurrent.SignallingRef

/**
  * Keeps track of a list of project signals based on given values
  */
final class ProjectSignals[A](
    values: AtomicCell[IO, Map[ProjectRef, A]],
    signals: AtomicCell[IO, Map[ProjectRef, SignallingRef[IO, Boolean]]]
) {

  /**
    * Return the signal for the given project
    */
  def get(project: ProjectRef): IO[Option[SignallingRef[IO, Boolean]]] =
    signals.get.map(_.get(project))

  /**
    * Push updates for values and recompute the signals for all values with the new predicate
    */
  def refresh(updates: Map[ProjectRef, A], predicate: A => Boolean): IO[Unit] =
    values.updateAndGet(_ ++ updates).flatMap { updatedValues =>
      signals.evalUpdate { signals =>
        updatedValues.toList.foldLeftM(emptySignals) { case (acc, (project, value)) =>
          val b = predicate(value)
          signals.get(project) match {
            case Some(signal) => signal.set(b).as(acc.updated(project, signal))
            case None         =>
              SignallingRef.of[IO, Boolean](b).map(acc.updated(project, _))
          }
        }
      }.void
    }

  /**
    * Return a snapshot of the signals for all the projects
    */
  def activityMap: IO[Map[ProjectRef, Boolean]] =
    signals.get.flatMap { signals =>
      signals.toList.foldLeftM(Map.empty[ProjectRef, Boolean]) { case (acc, (project, signal)) =>
        signal.get.map(acc.updated(project, _))
      }
    }
}

object ProjectSignals {

  val emptySignals = Map.empty[ProjectRef, SignallingRef[IO, Boolean]]

  def apply[A]: IO[ProjectSignals[A]] =
    for {
      values  <- AtomicCell[IO].of(Map.empty[ProjectRef, A])
      signals <- AtomicCell[IO].of(emptySignals)
    } yield new ProjectSignals[A](values, signals)

}
