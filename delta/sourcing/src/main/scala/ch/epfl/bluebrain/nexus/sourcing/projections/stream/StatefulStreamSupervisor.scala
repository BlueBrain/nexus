package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.util.Timeout
import cats.Monoid
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisorBehavior._
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler
import retry.CatsEffect._
import retry.syntax.all._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag

/**
  * A [[StreamSupervisor]] that keeps track of a state.
  */
class StatefulStreamSupervisor[A] private[projections] (
    ref: ActorRef[SupervisorCommand],
    retryStrategy: RetryStrategy[Throwable],
    askTimeout: Timeout
)(implicit A: ClassTag[A], as: ActorSystem[Nothing])
    extends StreamSupervisor {
  implicit private val timeout: Timeout = askTimeout

  import retryStrategy._

  /**
    * Stops the stream managed inside the current supervisor
    */
  def stop: Task[Unit] =
    Task.delay(ref ! Stop)

  /**
    * Fetches the current state of the stream
    */
  def state: Task[A] =
    Task
      .deferFuture(ref.ask(FetchState))
      .flatMap {
        case StateReply(A(state)) => Task.pure(state)
        case err: StateReplyError => Task.terminate(err)
      }
      .retryingOnSomeErrors(retryWhen)

}

// $COVERAGE-OFF$
object StatefulStreamSupervisor {

  /**
    * Runs a stream supervisor as a [[ClusterSingleton]] and keeps its state.
    *
    * @param name            the unique name for the singleton
    * @param streamTask      the embedded stream
    * @param retryStrategy   the strategy when the stream fails
    * @param askTimeout      the timeout to wait for replies from the underlying actor
    * @param onTerminate     Additional action when we stop the stream
    */
  def apply[A: ClassTag](
      name: String,
      streamTask: Task[Stream[Task, A]],
      retryStrategy: RetryStrategy[Throwable],
      askTimeout: FiniteDuration = 5.seconds,
      onTerminate: Option[Task[Unit]] = None
  )(implicit monoid: Monoid[A], as: ActorSystem[Nothing], scheduler: Scheduler): Task[StatefulStreamSupervisor[A]] =
    StreamState.record(monoid.empty).map { state =>
      val singletonManager = ClusterSingleton(as)
      val behavior         = stateful(name, streamTask, retryStrategy, state, askTimeout, onTerminate)
      val actorRef         = singletonManager.init {
        SingletonActor(Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart), name)
      }
      new StatefulStreamSupervisor[A](actorRef, retryStrategy, Timeout(askTimeout))
    }
}
// $COVERAGE-ON$
