package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task}

/**
  * A reference to a projection that has been started.
  *
  * @param name
  *   the name of the projection
  * @param status
  *   the projection execution status
  * @param signal
  *   a signal to stop the projection
  * @param fiber
  *   the projection fiber
  * @see
  *   [[ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionDef]]
  */
final class Projection private[stream] (
    val name: String,
    status: Ref[Task, ExecutionStatus],
    signal: SignallingRef[Task, Boolean],
    fiber: Ref[Task, Fiber[Throwable, Unit]]
) {

  /**
    * @return
    *   the current execution status of this projection
    */
  def executionStatus: Task[ExecutionStatus] =
    status.get

  /**
    * @return
    *   the last observed offset of this projection
    */
  def offset: Task[ProjectionOffset] =
    executionStatus.map(_.offset)

  /**
    * @return
    *   true if the projection is still running, false otherwise
    */
  def isRunning: Task[Boolean] =
    status.get.map(_.isRunning)

  /**
    * Stops the projection. Has no effect if the projection is already stopped.
    */
  def stop(): Task[Unit] =
    for {
      f <- fiber.get
      _ <- status.update(_.stopped)
      _ <- signal.set(true)
      _ <- f.join
    } yield ()
}
