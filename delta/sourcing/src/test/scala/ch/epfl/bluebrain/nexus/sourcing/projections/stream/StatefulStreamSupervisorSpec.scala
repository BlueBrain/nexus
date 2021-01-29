package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisorBehavior.stateful
import ch.epfl.bluebrain.nexus.testkit.IOValues
import fs2.Stream
import monix.bio.Task
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class StatefulStreamSupervisorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Eventually
    with IOValues
    with StreamSupervisorBehaviorsSpec {
  override def supervisor[A](
      stream: Stream[Task, A],
      retryStrategy: RetryStrategy[Throwable],
      onFinalize: Option[Task[Unit]]
  ): Task[(StreamSupervisor, ActorRef[StreamSupervisorBehavior.SupervisorCommand])] =
    StreamState.record[A, Int](0, (state, _) => state + 1).map { state =>
      val behavior = stateful("test", Task(stream), retryStrategy, state, 3.seconds, onFinalize)
      val ref      = actorRef(behavior)
      (new StatefulStreamSupervisor[Long](ref, retryStrategy, Timeout(3.seconds)), ref)
    }

  "A StatefulStreamSupervisor" should {

    "return the current state" in {
      var list                                                 = List.empty[String]
      val stream                                               = Stream[Task, String]("a", "b", "c").repeat.metered(100.millis).map { entry =>
        list = list.appended(entry)
        entry
      }
      val next: (Map[String, Int], String) => Map[String, Int] =
        (state, entry) => state.updatedWith(entry)(value => Some(value.getOrElse(0) + 1))

      val supervisor = StreamState
        .record(Map.empty[String, Int], next)
        .map { state =>
          val behavior = stateful("test", Task(stream), RetryStrategy.alwaysGiveUp, state, 3.seconds)
          val ref      = actorRef(behavior)
          new StatefulStreamSupervisor[Map[String, Int]](ref, RetryStrategy.alwaysGiveUp, Timeout(3.seconds))
        }
        .accepted

      val state = (Task.sleep(550.millis) >> supervisor.state <* supervisor.stop).accepted
      state should not be empty
      state shouldEqual list.foldLeft(Map.empty[String, Int])(next)
    }

  }
}
