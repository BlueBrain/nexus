package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

sealed trait CopyResult extends Product with Serializable

object CopyResult {

  final case object Success extends CopyResult

  final case object AlreadyExists extends CopyResult

}
