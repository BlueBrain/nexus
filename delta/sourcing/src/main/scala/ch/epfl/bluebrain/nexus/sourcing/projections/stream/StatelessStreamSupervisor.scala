package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisorBehavior._
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

import scala.reflect.ClassTag

/**
  * A [[StreamSupervisor]] that does not keep track of a state.
  */
class StatelessStreamSupervisor private[projections] (ref: ActorRef[SupervisorCommand]) extends StreamSupervisor {

  /**
    * Stops the stream managed inside the current supervisor
    */
  def stop: Task[Unit] =
    Task.delay(ref ! Stop)
}

// $COVERAGE-OFF$
object StatelessStreamSupervisor {

  /**
    * Runs a stream supervisor as a [[ClusterSingleton]] and ignores its state.
    *
    * @param name            the unique name for the singleton
    * @param streamTask      the embedded stream
    * @param retryStrategy   the strategy when the stream fails
    * @param onTerminate     Additional action when we stop the stream
    */
  def apply[A, S: ClassTag](
      name: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      onTerminate: Option[Task[Unit]] = None
  )(implicit as: ActorSystem[Nothing], scheduler: Scheduler): Task[StatelessStreamSupervisor] =
    Task.delay {
      val singletonManager = ClusterSingleton(as)
      val behavior         = stateless(name, streamTask, retryStrategy, onTerminate)
      val actorRef         = singletonManager.init {
        SingletonActor(Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart), name)
      }
      new StatelessStreamSupervisor(actorRef)
    }
}
// $COVERAGE-ON$
