package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor.Stop
import fs2.Stream
import monix.bio.Task
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class StreamSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Eventually {

  import monix.execution.Scheduler.Implicits.global

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

      val supervisor = testKit.spawn(
        StreamSupervisor.behavior(
          Task { stream },
          RetryStrategy.alwaysGiveUp,
          Some(Task { finalizeHappened = true })
        )
      )

      eventually {
        (Task.sleep(500.millis) >> Task.delay { supervisor ! Stop }).runSyncUnsafe()
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

      val supervisor = testKit.spawn(
        StreamSupervisor.behavior(
          Task { stream },
          RetryStrategy.alwaysGiveUp,
          Some(finalize)
        )
      )

      eventually {
        (Task.sleep(60.millis) >> Task.delay { testKit.stop(supervisor) }).runSyncUnsafe()
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

      testKit.spawn(
        StreamSupervisor.behavior(Task { stream }, RetryStrategy.alwaysGiveUp, Some(Task { finalizeHappened = true }))
      )

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

      testKit.spawn(
        StreamSupervisor.behavior(
          Task { stream },
          RetryStrategy.constant(20.millis, 3, _ => true),
          Some(Task { numberOfRetries += 1 })
        )
      )

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

      testKit.spawn(
        StreamSupervisor.behavior(
          Task { stream },
          RetryStrategy.constant(20.millis, 3, retryWhen),
          Some(Task { numberOfRetries += 1 })
        )
      )

      eventually {
        list should not be empty
        numberOfRetries shouldBe 2
      }
    }
  }
}
