package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.typed.javadsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

object IndexingStreamCoordinatorBehavior {

  sealed trait IndexingStreamCoordinatorCommand[V]

  final case class StartIndexing[V](view: V) extends IndexingStreamCoordinatorCommand[V]

  final case class RestartIndexing[V](view: V) extends IndexingStreamCoordinatorCommand[V]

  final case class StopIndexing[V](view: V) extends IndexingStreamCoordinatorCommand[V]

  def apply[V, P](
      projection: Projection[P],
      idF: V => ViewProjectionId,
      buildStream: (V, ProjectionProgress[P]) => Task[Stream[Task, P]],
      retryStrategy: RetryStrategy[Throwable]
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Behavior[IndexingStreamCoordinatorCommand[V]] = apply(projection, retryStrategy, idF, buildStream, Map.empty)

  private def apply[V, P](
      projection: Projection[P],
      retryStrategy: RetryStrategy[Throwable],
      idF: V => ViewProjectionId,
      buildStream: (V, ProjectionProgress[P]) => Task[Stream[Task, P]],
      streams: Map[ViewProjectionId, StreamSupervisor]
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Behavior[IndexingStreamCoordinatorCommand[V]] = {
    case StartIndexing(view: V)   =>
      val id = idF(view)
      if (streams.contains(id))
        Behaviors.same
      else {
        val stream     = projection.progress(id).flatMap(buildStream(view, _))
        val supervisor = StreamSupervisor.applyNonDelayed(id.value, stream, retryStrategy)
        apply(projection, retryStrategy, idF, buildStream, streams.updated(id, supervisor))
      }
    case StopIndexing(view: V)    =>
      val id = idF(view)
      if (!streams.contains(id))
        Behaviors.same
      else {
        val supervisor = streams(id)
        supervisor.stop.runSyncUnsafe()
        apply(projection, retryStrategy, idF, buildStream, streams.removed(id))
      }
    case RestartIndexing(view: V) =>
      val id            = idF(view)
      if (!streams.contains(id)) {
        val oldSupervisor = streams(id)
        oldSupervisor.stop.runSyncUnsafe()
      }
      val stream        = projection.progress(id).flatMap(buildStream(view, _))
      val newSupervisor = StreamSupervisor.applyNonDelayed(id.value, stream, retryStrategy)
      apply(projection, retryStrategy, idF, buildStream, streams.updated(id, newSupervisor))
  }

}
