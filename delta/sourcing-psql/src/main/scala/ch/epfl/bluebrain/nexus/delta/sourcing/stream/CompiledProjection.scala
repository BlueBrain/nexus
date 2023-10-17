package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.OperationF.SinkF
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

  /**
    * Creates a projection from a provided task
    */
  def fromTask(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      task: Task[Unit]
  ): CompiledProjection =
    fromStream(metadata, executionStrategy, _ => Stream.eval(task).drain)

  /**
    * Creates a projection from a provided stream
    */
  def fromStream(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      stream: Offset => Stream[Task, Elem[Unit]]
  ): CompiledProjection =
    CompiledProjection(metadata, executionStrategy, offset => _ => _ => stream(offset))

  /**
    * Attempts to compile the projection with just a source and a sink.
    */
  def compile(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      source: Source,
      sink: SinkF[Task]
  ): Either[ProjectionErr, CompiledProjection] =
    source.through(sink).map { p =>
      CompiledProjection(metadata, executionStrategy, offset => _ => _ => p.apply(offset).map(_.void))
    }

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
      operations <- OperationF.merge(chain ++ NonEmptyChain.one(sink))
      result     <- source.through(operations)
    } yield CompiledProjection(metadata, executionStrategy, offset => _ => _ => result.apply(offset).map(_.void))

}
