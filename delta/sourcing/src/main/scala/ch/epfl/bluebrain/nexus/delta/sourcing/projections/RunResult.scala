package ch.epfl.bluebrain.nexus.delta.sourcing.projections

/**
  */
sealed trait RunResult extends Product with Serializable

object RunResult {
  final case object Success extends RunResult

  final case class Warning(message: String) extends RunResult
}
