package ch.epfl.bluebrain.nexus.delta.sourcing.stream

// TODO: add docs
sealed trait ExecutionStatus extends Product with Serializable {
  def offset: ProjectionOffset

  def running: ExecutionStatus.Running              = ExecutionStatus.Running(offset)
  def stopped: ExecutionStatus.Stopped              = ExecutionStatus.Stopped(offset)
  def passivated: ExecutionStatus.Passivated        = ExecutionStatus.Passivated(offset)
  def completed: ExecutionStatus.Completed          = ExecutionStatus.Completed(offset)
  def failed(th: Throwable): ExecutionStatus.Failed = ExecutionStatus.Failed(th, offset)

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

  def isStopped: Boolean = this match {
    case _: ExecutionStatus.Stopped => true
    case _                          => false
  }

  def isRunning: Boolean = this match {
    case _: ExecutionStatus.Running => true
    case _                          => false
  }
}

object ExecutionStatus {
  final case object Ignored                                        extends ExecutionStatus {
    override def offset: ProjectionOffset = ProjectionOffset.empty
  }
  final case class Pending(offset: ProjectionOffset)               extends ExecutionStatus
  final case class Running(offset: ProjectionOffset)               extends ExecutionStatus
  final case class Stopped(offset: ProjectionOffset)               extends ExecutionStatus
  final case class Passivated(offset: ProjectionOffset)            extends ExecutionStatus
  final case class Completed(offset: ProjectionOffset)             extends ExecutionStatus
  final case class Failed(th: Throwable, offset: ProjectionOffset) extends ExecutionStatus
}
