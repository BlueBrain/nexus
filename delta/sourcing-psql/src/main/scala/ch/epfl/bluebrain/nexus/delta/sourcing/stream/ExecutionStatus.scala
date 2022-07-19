package ch.epfl.bluebrain.nexus.delta.sourcing.stream

/**
  * Enumeration of projection execution statuses.
  */
sealed trait ExecutionStatus extends Product with Serializable {

  /**
    * @return
    *   the last observed offset of the projection
    */
  def offset: ProjectionOffset

  /**
    * @return
    *   a running status with the same offset as this
    */
  def running: ExecutionStatus.Running = ExecutionStatus.Running(offset)

  /**
    * @return
    *   a stopped status with the same offset as this
    */
  def stopped: ExecutionStatus.Stopped = ExecutionStatus.Stopped(offset)

  /**
    * @return
    *   a passivated status with the same offset as this
    */
  def passivated: ExecutionStatus.Passivated = ExecutionStatus.Passivated(offset)

  /**
    * @return
    *   a completed status with the same offset as this
    */
  def completed: ExecutionStatus.Completed = ExecutionStatus.Completed(offset)

  /**
    * @param th
    *   the error to set on the failed status
    * @return
    *   a failed status with the same offset as this and provided error
    */
  def failed(th: Throwable): ExecutionStatus.Failed = ExecutionStatus.Failed(th, offset)

  /**
    * Updates the offset with the provided function.
    * @param f
    *   the fn to apply to the current offset
    * @return
    *   a new status with the modified offset
    */
  def updateOffset(f: ProjectionOffset => ProjectionOffset): ExecutionStatus =
    this match {
      case ExecutionStatus.Ignored            => ExecutionStatus.Ignored
      case ExecutionStatus.Pending(offset)    => ExecutionStatus.Pending(f(offset))
      case ExecutionStatus.Running(offset)    => ExecutionStatus.Running(f(offset))
      case ExecutionStatus.Stopped(offset)    => ExecutionStatus.Stopped(f(offset))
      case ExecutionStatus.Passivated(offset) => ExecutionStatus.Passivated(f(offset))
      case ExecutionStatus.Completed(offset)  => ExecutionStatus.Completed(f(offset))
      case ExecutionStatus.Failed(th, offset) => ExecutionStatus.Failed(th, f(offset))
    }

  /**
    * @return
    *   true if the status is [[ExecutionStatus.Stopped]], false otherwise
    */
  def isStopped: Boolean = this match {
    case _: ExecutionStatus.Stopped => true
    case _                          => false
  }

  /**
    * @return
    *   true if the status is [[ExecutionStatus.Running]], false otherwise
    */
  def isRunning: Boolean = this match {
    case _: ExecutionStatus.Running => true
    case _                          => false
  }
}

object ExecutionStatus {

  /**
    * Status for projections that are ignored by the supervision.
    */
  final case object Ignored extends ExecutionStatus {
    override def offset: ProjectionOffset = ProjectionOffset.empty
  }

  /**
    * Status for projections that are prepared for executions.
    * @param offset
    *   the last observed/known offset of the projection
    */
  final case class Pending(offset: ProjectionOffset) extends ExecutionStatus

  /**
    * Status for projections that are running.
    * @param offset
    *   the last observed/known offset of the projection
    */
  final case class Running(offset: ProjectionOffset) extends ExecutionStatus

  /**
    * Status for projections that have stopped.
    * @param offset
    *   the last observed/known offset of the projection
    */
  final case class Stopped(offset: ProjectionOffset) extends ExecutionStatus

  /**
    * Status for projections that have passivated.
    * @param offset
    *   the last observed/known offset of the projection
    */
  final case class Passivated(offset: ProjectionOffset) extends ExecutionStatus

  /**
    * Status for projections that have completed.
    * @param offset
    *   the last observed/known offset of the projection
    */
  final case class Completed(offset: ProjectionOffset) extends ExecutionStatus

  /**
    * Status for projections that have failed.
    * @param th
    *   the error that failed the projection
    * @param offset
    *   the last observed/known offset of the projection
    */
  final case class Failed(th: Throwable, offset: ProjectionOffset) extends ExecutionStatus
}
