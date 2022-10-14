package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.ExitCase
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ProgressConfig
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task, UIO}

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
    progress: Ref[Task, ProjectionProgress],
    signal: SignallingRef[Task, Boolean],
    fiber: Ref[Task, Fiber[Throwable, Unit]]
) {

  /**
    * @return
    *   the current execution status of this projection
    */
  def executionStatus: Task[ExecutionStatus] =
    status.get

  def currentProgress: Task[ProjectionProgress] = progress.get

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
      _ <- status.update(_ => ExecutionStatus.Stopped)
      _ <- signal.set(true)
      _ <- f.join
    } yield ()
}

object Projection {
    def apply(projection: CompiledProjection,
              fetchProgress: UIO[Option[ProjectionProgress]],
              saveProgress: ProjectionProgress => UIO[Unit])(implicit progressConfig: ProgressConfig): Task[Projection] =
      for {
        status    <- Ref[Task].of[ExecutionStatus](ExecutionStatus.Pending)
        signal   <- SignallingRef[Task, Boolean](false)
        progress <- fetchProgress.map(_.getOrElse(ProjectionProgress.NoProgress))
        progressRef <- Ref[Task].of(progress)
        stream    = projection.streamF
          .apply(progress.offset)(status)(signal)
          .interruptWhen(signal)
          .onFinalizeCaseWeak {
            case ExitCase.Error(th) => status.update(_.failed(th))
            case ExitCase.Completed => Task.unit // streams stopped through a signal still finish as Completed
            case ExitCase.Canceled  => Task.unit // the status is updated by the logic that cancels the stream
          }.mapAccumulate(progress) {
          case (acc, msg) if msg.offset.value > progress.offset.value => (acc + msg, msg)
          case (acc, msg)                                  => (acc, msg)
        }.groupWithin(progressConfig.maxElements, progressConfig.maxInterval).evalTap { elements =>
          elements.last.fold(Task.unit) { case (newProgress, _) =>
            progressRef.set(newProgress) >> saveProgress(newProgress)
          }
        }
          .compile
          .drain
        // update status to Running at the beginning and to Completed at the end if it's still running
        task      = status.update(_ => ExecutionStatus.Running) >> stream >> status.update(s => if (s.isRunning) ExecutionStatus.Completed else s)
        fiber    <- task.start
        fiberRef <- Ref[Task].of(fiber)
      } yield new Projection(projection.metadata.name, status, progressRef, signal, fiberRef)

}
