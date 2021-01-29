package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisorBehavior.stateless
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.Task
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

class StatelessStreamSupervisorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Eventually
    with IOValues
    with StreamSupervisorBehaviorsSpec {
  override def supervisor[A](
      stream: fs2.Stream[Task, A],
      retryStrategy: RetryStrategy[Throwable],
      onFinalize: Option[Task[Unit]]
  ): Task[(StreamSupervisor, ActorRef[StreamSupervisorBehavior.SupervisorCommand])] =
    Task.delay {
      val behavior = stateless("test", Task(stream), retryStrategy, onFinalize)
      val ref      = actorRef(behavior)
      (new StatelessStreamSupervisor(ref), ref)
    }
}
