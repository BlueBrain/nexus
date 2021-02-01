package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ActorRef, Behavior}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisorBehavior.SupervisorCommand
import ch.epfl.bluebrain.nexus.testkit.IOValues
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

trait StreamSupervisorBehaviorsSpec {
  this: ScalaTestWithActorTestKit with AnyWordSpecLike with Eventually with IOValues =>
  implicit val sc: Scheduler = Scheduler.global

  def actorRef(behavior: Behavior[SupervisorCommand]): ActorRef[SupervisorCommand] = testKit.spawn(behavior)

  def supervisor[A](
      stream: Stream[Task, A],
      retryStrategy: RetryStrategy[Throwable],
      onFinalize: Option[Task[Unit]]
  ): Task[(StreamSupervisor, ActorRef[SupervisorCommand])]

  "Start and stop the StreamSupervisor and its stream" should {

    "work fine if we send a stop message" in {
      var list             = List.empty[Long]
      var finalizeHappened = false

      val stream = Stream
        .repeatEval {
          Task {
            list = list.appended(System.currentTimeMillis)
          }
        }
        .metered(20.millis)

      val (streamSupervisor, _) =
        supervisor(stream, RetryStrategy.alwaysGiveUp, Some(Task { finalizeHappened = true })).accepted

      eventually {
        (Task.sleep(500.millis) >> streamSupervisor.stop).runSyncUnsafe()
        list should not be empty
        finalizeHappened shouldBe true
      }
    }

    "work fine if the actor gets stopped " in {
      var list             = List.empty[Long]
      var finalizeHappened = false

      val stream = Stream
        .repeatEval {
          Task { list = list.appended(System.currentTimeMillis) }
        }
        .metered(20.millis)

      val finalize = Task { finalizeHappened = true }

      val (_, actorSupervisor) =
        supervisor(stream, RetryStrategy.alwaysGiveUp, Some(finalize)).accepted

      eventually {
        (Task.sleep(60.millis) >> Task.delay { testKit.stop(actorSupervisor) }).runSyncUnsafe()
        list should not be empty
        finalizeHappened shouldBe true
      }
    }
  }

  "Retry policy" should {
    "stop the stream if the stream fails and the retry policy doesn't apply" in {
      var list             = List.empty[Long]
      var finalizeHappened = false

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task { list = list.appended(l) }
      } ++ Stream.raiseError[Task] {
        new Exception("Oops, something went wrong")
      }

      supervisor(stream, RetryStrategy.alwaysGiveUp, Some(Task { finalizeHappened = true })).accepted

      eventually {
        list should not be empty
        finalizeHappened shouldBe true
      }
    }

    "restart the stream if the stream fails as long as the maxRetries in the retry policy doesn't apply" in {
      var list            = List.empty[Long]
      var numberOfRetries = 0

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task { list = list.appended(l) }
      } ++ Stream.raiseError[Task] {
        new Exception("Oops, something went wrong")
      }

      supervisor(stream, RetryStrategy.constant(20.millis, 3, _ => true), Some(Task { numberOfRetries += 1 })).accepted

      eventually {
        list should not be empty
        numberOfRetries shouldBe 3
      }
    }

    "stop the stream if the error doesn't satisfy the predicate for a retry" in {
      var list            = List.empty[Long]
      var numberOfRetries = 0

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task { list = list.appended(l) }
      } ++ Stream.raiseError[Task] {
        numberOfRetries match {
          case 0 => new IllegalArgumentException("Fail")
          case _ => new NullPointerException("Fail")
        }
      }

      def retryWhen(t: Throwable): Boolean =
        t match {
          case _: IllegalArgumentException => true
          case _                           => false
        }

      supervisor(stream, RetryStrategy.constant(20.millis, 3, retryWhen), Some(Task { numberOfRetries += 1 })).accepted

      eventually {
        list should not be empty
        numberOfRetries shouldBe 2
      }
    }
  }

}
