package ch.epfl.bluebrain.nexus.delta.sourcing

sealed trait EvaluationResult extends Product with Serializable

object EvaluationResult {

  final case class EvaluationSuccess[Event, State](event: Event, state: State) extends EvaluationResult

  final case class EvaluationRejection[Rejection](value: Rejection) extends EvaluationResult
}
