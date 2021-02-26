package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingCommand.{RestartIndexing, StartIndexing, StopIndexing}
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingState._
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator.Agg
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.StopStrategy.TransientStopStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.{EventSourceProcessorConfig, ShardedAggregate}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.delta.sourcing.{Aggregate, TransientEventDefinition}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import java.util.UUID
import scala.reflect.ClassTag

trait IndexingStreamCoordinator[V] {

  /**
    * Start indexing the passed ''view''
    */
  def start(view: V): UIO[Unit]

  /**
    * Restarts indexing the passed ''view'' from the beginning
    */
  def restart(view: V): UIO[Unit]

  /**
    * Stop indexing the passed ''view''
    */
  def stop(view: V): UIO[Unit]
}

/**
  * It manages the lifecycle of each [[StreamSupervisor]] of each view in the system.
  * Each [[StreamSupervisor]] runs a stream that indexes data. The need for the [[StreamSupervisor]] comes from the fact
  * that we want to make sure only one node runs indexing in a multi-node environment, making use of Akka Cluster Singleton.
  *
  * We use an underlying transient [[ShardedAggregate]] to keep track of each views' [[StreamSupervisor]].
  * With a simple state machine we can handle starts/restarts/stops
  */
class IndexingStreamCoordinatorImpl[V: ViewLens] private[indexing] (agg: Agg) extends IndexingStreamCoordinator[V] {
  implicit private val logger = Logger[IndexingStreamCoordinator.type]

  private def shardedId(view: V) = view.uuid.toString

  def start(view: V): UIO[Unit] =
    agg
      .evaluate(shardedId(view), StartIndexing(view))
      .mapError(_.value)
      .logAndDiscardErrors(s"starting view '${view.uuid}'")
      .void

  def restart(view: V): UIO[Unit] =
    agg
      .evaluate(shardedId(view), RestartIndexing(view))
      .mapError(_.value)
      .logAndDiscardErrors(s"restarting view '${view.uuid}'")
      .void

  def stop(view: V): UIO[Unit] =
    agg
      .evaluate(shardedId(view), StopIndexing)
      .mapError(_.value)
      .logAndDiscardErrors(s"stopping view '${view.uuid}'")
      .void

}

object IndexingStreamCoordinator {

  type BuildStream[V] = (V, ProjectionProgress[Unit]) => Task[Stream[Task, Unit]]

  type ClearIndex = String => Task[Unit]

  private[indexing] type Agg = Aggregate[String, IndexingState, IndexingCommand, IndexingState, Throwable]

  /**
    * Construct a [[IndexingStreamCoordinator]] relying on the underlying transient [[ShardedAggregate]]
    */
  def apply[V: ViewLens](
      entityType: String,
      buildStream: BuildStream[V],
      clearIndex: ClearIndex,
      projection: Projection[Unit],
      config: EventSourceProcessorConfig,
      retryStrategy: RetryStrategy[Throwable]
  )(implicit V: ClassTag[V], as: ActorSystem[Nothing], sc: Scheduler): Task[IndexingStreamCoordinator[V]] = {

    def supervisorName(view: V) =
      s"${view.uuid}_${view.rev}_${UUID.randomUUID()}"

    def start(view: V): Task[IndexingState] = {
      val stream = projection.progress(view.projectionId).flatMap(buildStream(view, _))
      StreamSupervisor(supervisorName(view), stream, retryStrategy).map(Current(view.index, view.rev, _))
    }

    def startFromBeginning(view: V): Task[IndexingState] =
      StreamSupervisor(
        supervisorName(view),
        buildStream(view, ProjectionProgress.NoProgress),
        retryStrategy
      )
        .map(Current(view.index, view.rev, _))

    def eval: (IndexingState, IndexingCommand) => Task[IndexingState] = {
      case (Initial, StartIndexing(V(view)))                             => start(view)
      case (cur: Current, StartIndexing(V(view))) if view.rev == cur.rev => Task.pure(cur)
      case (cur: Current, StartIndexing(V(view)))                        => cur.supervisor.stop() >> clearIndex(cur.index) >> start(view)
      case (Initial, StopIndexing)                                       => Task.pure(Initial)
      case (cur: Current, StopIndexing)                                  => cur.supervisor.stop().as(Initial)
      case (Initial, RestartIndexing(V(view)))                           => startFromBeginning(view)
      case (cur: Current, RestartIndexing(V(view)))                      => cur.supervisor.stop() >> startFromBeginning(view)
      case (_, StartIndexing(o))                                         => Task.raiseError(new IllegalArgumentException(s"Wrong type '${simpleName(o)}'"))
      case (_, RestartIndexing(o))                                       => Task.raiseError(new IllegalArgumentException(s"Wrong type '${simpleName(o)}'"))
    }

    val definition = TransientEventDefinition.cache(entityType, Initial, eval, TransientStopStrategy.never)
    ShardedAggregate.transientSharded(definition, config, Some(retryStrategy)).map(new IndexingStreamCoordinatorImpl(_))
  }

}
