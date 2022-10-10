package ch.epfl.bluebrain.nexus.delta.sourcing.stream

/**
  * Enumeration of projection execution statuses.
  */
sealed trait ExecutionStatus extends Product with Serializable {
  /**
    * @param th
    *   the error to set on the failed status
    * @return
    *   a failed status with the same offset as this and provided error
    */
  def failed(th: Throwable): ExecutionStatus.Failed = ExecutionStatus.Failed(th)

  /**
    * @return
    *   true if the status is [[ExecutionStatus.Stopped]], false otherwise
    */
  def isStopped: Boolean = false

  /**
    * @return
    *   true if the status is [[ExecutionStatus.Running]], false otherwise
    */
  def isRunning: Boolean = false
}

object ExecutionStatus {

  /**
    * Status for projections that are ignored by the supervision.
    */
  final case object Ignored extends ExecutionStatus

  /**
    * Status for projections that are prepared for executions.
    */
  final case object Pending extends ExecutionStatus

  /**
    * Status for projections that are running.
    */
  final case object Running extends ExecutionStatus {
    override def isRunning: Boolean = true
  }

  /**
    * Status for projections that have stopped.
    */
  final case object Stopped extends ExecutionStatus {
    override def isStopped: Boolean = true
  }

  /**
    * Status for projections that have passivated.
    */
  final case object Passivated extends ExecutionStatus

  /**
    * Status for projections that have completed.
    */
  final case object Completed extends ExecutionStatus

  /**
    * Status for projections that have failed.
    * @param th
    *   the error that failed the projection
    */
  final case class Failed(th: Throwable) extends ExecutionStatus
}
