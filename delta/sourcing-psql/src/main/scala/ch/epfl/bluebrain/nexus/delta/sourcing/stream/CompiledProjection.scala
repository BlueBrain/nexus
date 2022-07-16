package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.ExitCase
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
  *   a fn that produces a stream given a starting offset, a status reference and a stop signal
  * @see
  *   [[ProjectionDef]]
  */
final case class CompiledProjection private[stream] (
    name: String,
    project: Option[ProjectRef],
    resourceId: Option[Iri],
    streamF: ProjectionOffset => Ref[Task, ExecutionStatus] => SignallingRef[Task, Boolean] => Stream[Task, Elem[Unit]]
) {

  /**
    * Supervises the execution of the provided `projection` using the provided `executionStrategy`. A second call to
    * this method with a projection with the same name will cause the current projection to be stopped and replaced by
    * the new one.
    * @param supervisor
    *   the projection supervisor
    * @param executionStrategy
    *   the strategy for the projection execution
    * @see
    *   [[Supervisor]]
    */
  def supervise(supervisor: Supervisor, executionStrategy: ExecutionStrategy): Task[Unit] =
    supervisor.supervise(this, executionStrategy)

  /**
    * Transforms this projection such that is passivates gracefully when it becomes idle. A projection is considered
    * idle when there are no elements that have been processed within the defined `inactiveInterval`.
    * @param inactiveInterval
    *   the idle interval after which a projection should be passivated
    * @param checkInterval
    *   how frequent to check if the projection becomes idle
    */
  def passivate(inactiveInterval: FiniteDuration, checkInterval: FiniteDuration): CompiledProjection =
    copy(streamF =
      offset =>
        status =>
          signal =>
            streamF(offset)(status)(signal)
              .through(Passivation(inactiveInterval, checkInterval, status, () => signal.set(true)))
    )

  /**
    * Transforms this projection such that it persists the observed offsets at regular intervals. For each tick defined
    * by the interval the last known written offset is compared with the offset read; if there are differences the
    * current offset is not written and the projection is stopped. Additionally, the offset is persisted only if the
    * offset reported by the projection is different from the last known written offset.
    * @param store
    *   the store to use for persisting offsets
    * @param interval
    *   the interval at which the offset should be persisted if there are differences
    */
  def persistOffset(store: ProjectionStore, interval: FiniteDuration): CompiledProjection =
    persistOffset(store.persistFn(name, project, resourceId), store.readFn(name), interval)

  /**
    * Transforms this projection such that it persists the observed offsets at regular intervals. For each tick defined
    * by the interval the last known written offset is compared with the offset read; if there are differences the
    * current offset is not written and the projection is stopped. Additionally, the offset is persisted only if the
    * offset reported by the projection is different from the last known written offset.
    * @param persistOffsetFn
    *   the fn to persist an offset
    * @param readOffsetFn
    *   the fn to read the persisted offset
    * @param interval
    *   the interval at which the offset should be persisted if there are differences
    */
  def persistOffset(
      persistOffsetFn: ProjectionOffset => Task[Unit],
      readOffsetFn: () => Task[ProjectionOffset],
      interval: FiniteDuration
  ): CompiledProjection =
    copy(streamF =
      offset =>
        status =>
          signal =>
            streamF(offset)(status)(signal)
              .through(PersistOffset(offset, interval, persistOffsetFn, readOffsetFn, status, () => signal.set(true)))
    )

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
      status     <- Ref[Task].of[ExecutionStatus](ExecutionStatus.Running(offset))
      projection <- start(offset, status)
    } yield projection

  /**
    * Starts the projection from the provided offset. The stream is executed in the background and can be interacted
    * with using the [[Projection]] methods.
    * @param offset
    *   the offset to be used for starting the projection
    * @param status
    *   a reference to hold the status of the projection
    * @return
    *   the materialized running [[Projection]]
    */
  def start(offset: ProjectionOffset, status: Ref[Task, ExecutionStatus]): Task[Projection] =
    for {
      signal   <- SignallingRef[Task, Boolean](false)
      stream    = streamF
                    .apply(offset)(status)(signal)
                    .evalTap { elem =>
                      status.update(_.updateOffset(current => current.add(elem.ctx, elem.offset)))
                    }
                    .interruptWhen(signal)
                    .onFinalizeCaseWeak {
                      case ExitCase.Error(th) => status.update(_.failed(th))
                      case ExitCase.Completed => Task.unit // streams stopped through a signal still finish as Completed
                      case ExitCase.Canceled  => Task.unit // the status is updated by the logic that cancels the stream
                    }
                    .compile
                    .drain
      // update status to Running at the beginning and to Completed at the end if it's still running
      task      = status.update(_.running) >> stream >> status.update(s => if (s.isRunning) s.completed else s)
      fiber    <- task.start
      fiberRef <- Ref[Task].of(fiber)
    } yield new Projection(name, status, signal, fiberRef)

  /**
    * Starts the projection from the beginning. The stream is executed in the background and can be interacted with
    * using the [[Projection]] methods.
    * @return
    *   the materialized running [[Projection]]
    */
  def start(): Task[Projection] =
    start(ProjectionOffset.empty)

  /**
    * Starts the projection from the beginning. The stream is executed in the background and can be interacted with
    * using the [[Projection]] methods.
    * @param status
    *   a reference to hold the status of the projection
    * @return
    *   the materialized running [[Projection]]
    */
  def start(status: Ref[Task, ExecutionStatus]): Task[Projection] =
    start(ProjectionOffset.empty, status)
}
