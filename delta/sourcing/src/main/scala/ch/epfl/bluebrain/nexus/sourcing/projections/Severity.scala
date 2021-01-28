package ch.epfl.bluebrain.nexus.sourcing.projections

/**
  * Severity of a [[ProjectionError]]
  */
sealed trait Severity

object Severity {

  /**
    * Warning, the error did not prevent the projection to pursue
    */
  final case object Warning extends Severity

  /**
    * Failure, the error prevented the projection to complete
    */
  final case object Failure extends Severity

  /**
    * Parses the severity from a string
    */
  def fromString(value: String): Severity = value match {
    case "Warning" => Warning
    case _         => Failure
  }

}
