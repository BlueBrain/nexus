package ch.epfl.bluebrain.nexus.delta.sdk

sealed trait ExecutionType extends Product with Serializable

object ExecutionType {

  final case object Performant extends ExecutionType
  final case object Consistent extends ExecutionType
}
