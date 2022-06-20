package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task}

/**
  * A reference to a projection that has been started.
  *
  * @param name
  *   the name of the projection
  * @param offset
  *   the last observed [[ProjectionOffset]]
  * @param signal
  *   a signal to stop the projection
  * @param finalised
  *   whether the projection has finalised
  * @param fiber
  *   the projection fiber
  * @see
  *   [[ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionDef]]
  */
final class Projection private[stream] (
    val name: String,
    offset: Ref[Task, ProjectionOffset],
    signal: SignallingRef[Task, Boolean],
    finalised: Ref[Task, Boolean],
    fiber: Ref[Task, Fiber[Throwable, Unit]]
) {

  /**
    * @return
    *   a hash value for this projection to be used for balancing projections across nodes
    */
  def hash: Int = name.hashCode

  /**
    * @return
    *   true if the projection is still running, false otherwise
    */
  def isRunning: Task[Boolean] =
    finalised.get.map(finalised => !finalised)

  /**
    * Stops the projection. Has no effect if the projection is already stopped.
    */
  def stop(): Task[Unit] =
    for {
      f <- fiber.get
      _ <- signal.set(true)
      _ <- f.join
    } yield ()

  /**
    * @return
    *   the last observed offset of this projection
    */
  def offset(): Task[ProjectionOffset] =
    offset.get
}
