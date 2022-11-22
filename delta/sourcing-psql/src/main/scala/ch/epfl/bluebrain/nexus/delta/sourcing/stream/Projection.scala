package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.ExitCase
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import fs2.Chunk
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task, UIO}

import scala.concurrent.duration.FiniteDuration

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

  /**
    * Return the current progress for this projection
    * @return
    */
  def currentProgress: Task[ProjectionProgress] = progress.get

  /**
    * @return
    *   true if the projection is still running, false otherwise
    */
  def isRunning: Task[Boolean] =
    status.get.map(_.isRunning)

  /**
    * Wait for the projection to complete within the defined timeout
    * @param timeout
    *   the maximum time expected for the projection to complete
    * @return
    */
  def waitForCompletion(timeout: FiniteDuration): Task[ExecutionStatus] =
    executionStatus
      .restartUntil {
        case ExecutionStatus.Completed => true
        case ExecutionStatus.Failed(_) => true
        case ExecutionStatus.Stopped   => true
        case _                         => false
      }
      .timeout(timeout)
      .flatMap(_ => executionStatus)

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

  def apply(
      projection: CompiledProjection,
      fetchProgress: UIO[Option[ProjectionProgress]],
      saveProgress: ProjectionProgress => UIO[Unit],
      saveFailedElems: List[FailedElem] => UIO[Unit]
  )(implicit batch: BatchConfig): Task[Projection] =
    for {
      status      <- Ref[Task].of[ExecutionStatus](ExecutionStatus.Pending)
      signal      <- SignallingRef[Task, Boolean](false)
      progress    <- fetchProgress.map(_.getOrElse(ProjectionProgress.NoProgress))
      progressRef <- Ref[Task].of(progress)
      stream       = projection.streamF
                       .apply(progress.offset)(status)(signal)
                       .interruptWhen(signal)
                       .onFinalizeCaseWeak {
                         case ExitCase.Error(th) => status.update(_.failed(th))
                         case ExitCase.Completed => Task.unit // streams stopped through a signal still finish as Completed
                         case ExitCase.Canceled  => Task.unit // the status is updated by the logic that cancels the stream
                       }
                       .mapAccumulate(progress) {
                         case (acc, msg) if msg.offset.value > progress.offset.value => (acc + msg, msg)
                         case (acc, msg)                                             => (acc, msg)
                       }
                       .groupWithin(batch.maxElements, batch.maxInterval)
                       .evalTap(chunk => saveProgressAndFailedElems(chunk, progressRef, saveProgress, saveFailedElems))
                       .compile
                       .drain
      // update status to Running at the beginning and to Completed at the end if it's still running
      task         = status.update(_ => ExecutionStatus.Running) >> stream >> status.update(s =>
                       if (s.isRunning) ExecutionStatus.Completed else s
                     )
      fiber       <- task.start
      fiberRef    <- Ref[Task].of(fiber)
    } yield new Projection(projection.metadata.name, status, progressRef, signal, fiberRef)

  /**
    * Given a chunk of (progress, elem) saves the last progress and any failed elems.
    * @param chunk
    *   a fs2.Chunk of (progress, elem) tuples
    * @param progressRef
    *   progress reference
    * @param saveProgress
    *   function to save the progress
    * @param saveFailedElems
    *   function to save failed elems
    */
  private def saveProgressAndFailedElems(
      chunk: Chunk[(ProjectionProgress, Elem[Unit])],
      progressRef: Ref[Task, ProjectionProgress],
      saveProgress: ProjectionProgress => UIO[Unit],
      saveFailedElems: List[FailedElem] => UIO[Unit]
  ): Task[Unit] = {
    val failedElems = chunk.collect { case (_, elem: FailedElem) => elem }.toList

    chunk.last.traverse { case (newProgress, _) =>
      progressRef.set(newProgress) >>
        saveProgress(newProgress) >>
        UIO.when(failedElems.nonEmpty)(saveFailedElems(failedElems))
    }.void
  }
}
