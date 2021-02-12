package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingCommand.{StartIndexing, StopIndexing}
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingState._
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator.Agg
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.sourcing.processor.StopStrategy.TransientStopStrategy
import ch.epfl.bluebrain.nexus.sourcing.processor.{EventSourceProcessorConfig, ShardedAggregate}
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.sourcing.{Aggregate, TransientEventDefinition}
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

import scala.reflect.ClassTag

class IndexingStreamCoordinator[V: ViewLens] private (aggregate: Agg) {

  private def shardedId(view: V) = s"${view.uuid}"

  /**
    * Start indexing the passed ''view''
    */
  def start(view: V): Task[Unit] =
    aggregate.evaluate(shardedId(view), StartIndexing(view)).mapError(_.value) >> Task.unit

  /**
    * Stop indexing the passed ''view''
    */
  def stop(view: V): Task[Unit] =
    aggregate.evaluate(shardedId(view), StopIndexing).mapError(_.value) >> Task.unit

}

object IndexingStreamCoordinator {

  type BuildStream[V] = (V, ProjectionProgress[Unit]) => Task[Stream[Task, Unit]]

  private[indexing] type Agg = Aggregate[String, IndexingState, IndexingCommand, IndexingState, Throwable]

  /**
    * Construct a [[IndexingStreamCoordinator]] relying on the underlying transient [[ShardedAggregate]]
    */
  def apply[V: ViewLens](
      entityType: String,
      buildStream: BuildStream[V],
      projection: Projection[Unit],
      config: EventSourceProcessorConfig,
      retryStrategy: RetryStrategy[Throwable]
  )(implicit V: ClassTag[V], as: ActorSystem[Nothing], sc: Scheduler): Task[IndexingStreamCoordinator[V]] = {

    def start(view: V): Task[IndexingState] = {
      val stream = projection.progress(view.projectionId).flatMap(buildStream(view, _))
      StreamSupervisor(s"${view.uuid}_${view.rev}", stream, retryStrategy).map(Current(view.rev, _))
    }

    def eval: (IndexingState, IndexingCommand) => Task[IndexingState] = {
      case (Initial, StartIndexing(V(view)))                             => start(view)
      case (cur: Current, StartIndexing(V(view))) if view.rev == cur.rev => Task.pure(cur)
      case (cur: Current, StartIndexing(V(view)))                        => cur.supervisor.stop >> start(view)
      case (Initial, StopIndexing)                                       => Task.pure(Initial)
      case (cur: Current, StopIndexing)                                  => cur.supervisor.stop.as(Initial)
      case (_, StartIndexing(o))                                         => Task.raiseError(new IllegalArgumentException(s"Wrong type '${simpleName(o)}'"))
    }

    val definition = TransientEventDefinition.cache(entityType, Initial, eval, TransientStopStrategy.never)
    ShardedAggregate.transientSharded(definition, config, retryStrategy).map(new IndexingStreamCoordinator(_))
  }

}
