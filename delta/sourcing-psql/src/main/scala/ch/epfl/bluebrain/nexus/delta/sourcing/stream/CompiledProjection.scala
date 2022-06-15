package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.concurrent.SignallingRef
import monix.bio.Task

import scala.annotation.nowarn

/**
  * A projection that has been successfully compiled and is ready to be run.
  *
  * @param name
  *   the name of the projection
  * @param source
  *   the underlying source that represents the stream
  * @param passivationStrategy
  *   a strategy for passivation
  * @param rebuildStrategy
  *   a strategy to rebuild the projection
  * @see
  *   [[ProjectionDef]]
  */
@nowarn("cat=unused")
@SuppressWarnings(Array("UnusedMethodParameter"))
final class CompiledProjection private[stream] (
    val name: String,
    source: Source.Aux[Unit],
    passivationStrategy: PassivationStrategy,
    rebuildStrategy: RebuildStrategy
) {

  /**
    * Starts the projection from the provided offset. The stream is executed in the background and can be interacted
    * with using the [[Projection]] methods.
    * @param offset
    *   the offset to be used for starting the projection
    * @return
    *   the materialized running [[Projection]]
    */
  def start(offset: ProjectionOffset): Task[Projection] =
    for {
      offsetRef <- Ref[Task].of(offset)
      signal    <- SignallingRef[Task, Boolean](false)
      // TODO handle failures with restarts, passivate after
      fiber     <- source
                     .apply(offset)
                     .evalTap { elem =>
                       offsetRef.getAndUpdate(current => current |+| ProjectionOffset(elem.value.ctx, elem.offset))
                     }
                     .interruptWhen(signal)
                     .compile
                     .drain
                     .start
      fiberRef  <- Ref[Task].of(fiber)
    } yield new Projection(name, offsetRef, signal, fiberRef)

  /**
    * Starts the projection from the beginning. The stream is executed in the background and can be interacted with
    * using the [[Projection]] methods.
    * @return
    *   the materialized running [[Projection]]
    */
  def start(): Task[Projection] =
    start(ProjectionOffset.empty)
}
