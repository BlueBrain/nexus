package ch.epfl.bluebrain.nexus.delta.sourcing.stream

final case class SupervisedDescription(
    metadata: ProjectionMetadata,
    executionStrategy: ExecutionStrategy,
    restarts: Int,
    status: ExecutionStatus,
    progress: ProjectionProgress
)
