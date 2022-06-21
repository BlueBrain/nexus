package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

/**
  * A projection that has been successfully compiled and is ready to be run.
  *
  * @param name
  *   the name of the projection
  * @param project
  *   an optional project reference associated with the projection
  * @param resourceId
  *   an optional resource id associated with the projection
  * @param streamF
  *   a fn that produces a stream given a starting offset
  * @see
  *   [[ProjectionDef]]
  */
final case class CompiledProjection private[stream] (
    name: String,
    project: Option[ProjectRef],
    resourceId: Option[Iri],
    streamF: ProjectionOffset => Stream[Task, Elem[Unit]]
) {

  /**
    * Transforms this projection such that is passivates gracefully when it becomes idle. A projection is considered
    * idle when there are no elements that have been processed within the defined `inactiveInterval`.
    * @param inactiveInterval
    *   the idle interval after which a projection should be passivated
    * @param checkInterval
    *   how frequent to check if the projection becomes idle
    */
  def passivate(inactiveInterval: FiniteDuration, checkInterval: FiniteDuration): CompiledProjection =
    copy(streamF = offset => streamF(offset).through(Passivation(inactiveInterval, checkInterval)))

  /**
    * Transforms this projection such that it persists the observed offsets at regular intervals.
    * @param store
    *   the store to use for persisting offsets
    * @param interval
    *   the interval at which the offset should be persisted if there are differences
    */
  def persistOffset(store: ProjectionStore, interval: FiniteDuration): CompiledProjection =
    persistOffset(store.persistFn(name, project, resourceId), interval)

  /**
    * Transforms this projection such that it persists the observed offsets at regular intervals.
    * @param persistOffsetFn
    *   the fn to persist an offset
    * @param interval
    *   the interval at which the offset should be persisted if there are differences
    */
  def persistOffset(persistOffsetFn: ProjectionOffset => Task[Unit], interval: FiniteDuration): CompiledProjection =
    copy(streamF = offset => streamF(offset).through(PersistOffset(offset, interval, persistOffsetFn)))

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
      offsetRef   <- Ref[Task].of(offset)
      finaliseRef <- Ref[Task].of(false)
      signal      <- SignallingRef[Task, Boolean](false)
      // TODO handle failures with restarts, passivate after
      fiber       <- streamF
                       .apply(offset)
                       .evalTap { elem =>
                         offsetRef.getAndUpdate(current => current.add(elem.ctx, elem.offset))
                       }
                       .interruptWhen(signal)
                       .onFinalizeWeak(finaliseRef.set(true))
                       .compile
                       .drain
                       .start
      fiberRef    <- Ref[Task].of(fiber)
    } yield new Projection(name, offsetRef, signal, finaliseRef, fiberRef)

  /**
    * Starts the projection from the beginning. The stream is executed in the background and can be interacted with
    * using the [[Projection]] methods.
    * @return
    *   the materialized running [[Projection]]
    */
  def start(): Task[Projection] =
    start(ProjectionOffset.empty)
}
