package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.Task

/**
  * A projection that has been successfully compiled and is ready to be run.
  *
  * @param metadata
  *   the metadata of the projection
  * @param streamF
  *   a fn that produces a stream given a starting offset, a status reference and a stop signal
  */
final case class CompiledProjection private (
    metadata: ProjectionMetadata,
    executionStrategy: ExecutionStrategy,
    streamF: Offset => Ref[Task, ExecutionStatus] => SignallingRef[Task, Boolean] => Stream[Task, Elem[Unit]]
)

object CompiledProjection {

  def fromStream(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      stream: Offset => Stream[Task, Elem[Unit]]
  ): CompiledProjection =
    CompiledProjection(metadata, executionStrategy, offset => _ => _ => stream(offset))

  /**
    * Attempts to compile the projection definition that can be later managed.
    */
  def compile(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      source: Source,
      chain: NonEmptyChain[Operation],
      sink: Sink
  ): Either[ProjectionErr, CompiledProjection] =
    for {
      operations <- Operation.merge(chain ++ NonEmptyChain.one(sink))
      result     <- source.through(operations)
    } yield CompiledProjection(metadata, executionStrategy, offset => _ => _ => result.apply(offset).map(_.void))

}
