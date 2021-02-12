package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinatorBehavior.{IndexingStreamCoordinatorCommand, RestartIndexing, StartIndexing, StopIndexing}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

class IndexingStreamCoordinator[V, P] private (
    ref: ActorRef[IndexingStreamCoordinatorCommand[V]],
    projection: Projection[P],
    idF: V => ViewProjectionId
) {

  def start(view: V): Task[Unit] = Task.delay(ref ! StartIndexing(view))

  def restart(view: V): Task[Unit] = Task.delay(ref ! RestartIndexing(view))

  def stop(view: V): Task[Unit] = Task.delay(ref ! StopIndexing(view))

  def progress(view: V): Task[ProjectionProgress[P]] = projection.progress(idF(view))

}

object IndexingStreamCoordinator {

  def apply[V, P](
      projection: Projection[P],
      idF: V => ViewProjectionId,
      buildStream: (V, ProjectionProgress[P]) => Task[Stream[Task, P]],
      retryStrategy: RetryStrategy[Throwable],
      name: String
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): IndexingStreamCoordinator[V, P] = {
    val singletonManager = ClusterSingleton(as)
    val behavior         = IndexingStreamCoordinatorBehavior(projection, idF, buildStream, retryStrategy)
    val actorRef         = singletonManager.init {
      SingletonActor(Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart), name)
    }
    new IndexingStreamCoordinator[V, P](actorRef, projection, idF)
  }
}
