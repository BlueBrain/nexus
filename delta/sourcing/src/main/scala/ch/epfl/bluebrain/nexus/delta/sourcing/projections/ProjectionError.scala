package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.Offset

import java.time.Instant

/**
  * Description a projection error
  */
sealed trait ProjectionError[A] {

  /**
    * @return the offset when the projection raised the error
    */
  def offset: Offset

  /**
    * @return the moment when the error was raised
    */
  def timestamp: Instant

  /**
    * @return the severity of the error
    */
  def severity: Severity

  /**
    * @return Description of the error
    */
  def message: String

  /**
    * @return the persistence id of the event
    */
  def persistenceId: String

  /**
    * @return the sequence of the event
    */
  def sequenceNr: Long

  /**
    * @return The value of the event
    */
  def value: Option[A]

}

object ProjectionError {

  final case class ProjectionFailure[A](
      offset: Offset,
      timestamp: Instant,
      message: String,
      persistenceId: String,
      sequenceNr: Long,
      value: Option[A],
      errorType: String
  ) extends ProjectionError[A] {
    override def severity: Severity = Severity.Failure
  }

  final case class ProjectionWarning[A](
      offset: Offset,
      timestamp: Instant,
      message: String,
      persistenceId: String,
      sequenceNr: Long,
      value: Option[A]
  ) extends ProjectionError[A] {
    override def severity: Severity = Severity.Warning
  }
}
