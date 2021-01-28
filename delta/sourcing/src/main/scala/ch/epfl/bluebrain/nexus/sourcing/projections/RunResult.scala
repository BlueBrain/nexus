package ch.epfl.bluebrain.nexus.sourcing.projections

/**
  */
sealed trait RunResult

object RunResult {
  final case object Success extends RunResult

  final case class Warning(message: String) extends RunResult
}
