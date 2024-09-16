package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

sealed trait S3OperationResult extends Product with Serializable

object S3OperationResult {

  final case object Success extends S3OperationResult

  final case object AlreadyExists extends S3OperationResult

}
