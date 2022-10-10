package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.{Pipe, Sink}
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
  * @see
  *   [[ProjectionDef]]
  */
final case class CompiledProjection private (
    metadata: ProjectionMetadata,
    executionStrategy: ExecutionStrategy,
    streamF: Offset => Ref[Task, ExecutionStatus] => SignallingRef[Task, Boolean] => Stream[Task, Elem[Unit]]
)

object CompiledProjection {

  def fromStream(metadata: ProjectionMetadata,
                 executionStrategy: ExecutionStrategy,
                 stream: Offset => Stream[Task, Elem[Unit]]): CompiledProjection =
    CompiledProjection(metadata, executionStrategy, offset => _ => _ => stream(offset))

  /**
    * Attempts to compile the projection definition that can be later managed.
    * @param registry
    *   the registry for looking up source and pipe references
    */
  def compile(definition: ProjectionDef,
              executionStrategy: ExecutionStrategy,
              registry: ReferenceRegistry): Either[ProjectionErr, CompiledProjection] =
    for {
      compiledSources <- definition.sources.traverse(_.compile(registry))
      mergedSources   <- compiledSources.tail.foldLeftM(compiledSources.head)((acc, e) => acc.merge(e))
      compiledPipes   <- definition.pipes.traverse(pc => PipeChain.compile(pc, registry))
      source          <- mergedSources.broadcastThrough(compiledPipes)
    } yield CompiledProjection(definition.metadata, executionStrategy, offset => _ => _ => source.apply(offset))

  /**
    * Attempts to compile the projection definition that can be later managed.
    */
  def compile(metadata: ProjectionMetadata,
              executionStrategy: ExecutionStrategy,
              source: Source,
              pipeChain: NonEmptyChain[Pipe],
              sink: Sink): Either[ProjectionErr, CompiledProjection] =
    for {
      operations   <- (pipeChain ++ NonEmptyChain.one(sink)).tail.foldLeftM[Either[ProjectionErr, *], Operation](pipeChain.head) { case (acc, e) =>
        acc.andThen(e)
      }
      result          <- source.through(operations)
    } yield CompiledProjection(metadata, executionStrategy, offset => _ => _ => result.apply(offset).map(_.void))

}
