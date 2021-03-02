package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisorBehavior.Stop
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike
import retry.RetryDetails

import scala.concurrent.duration._

class StreamSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Eventually {

  import monix.execution.Scheduler.Implicits.global

  val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => Task.unit

  "Start and stop the StreamSupervisor and its stream" should {

    "work fine if we send a stop message" in {
      var list             = List.empty[Long]
      var finalizeHappened = false

      val stream     = Stream
        .repeatEval {
          Task {
            list = list.appended(System.currentTimeMillis)
          }
        }
        .metered(20.millis)
      val ref        = Ref.of[Task, Boolean](false).runSyncUnsafe()
      val supervisor = testKit.spawn(
        StreamSupervisorBehavior(
          "streamName",
          Task { stream },
          RetryStrategy.alwaysGiveUp(onError),
          UIO { finalizeHappened = true },
          ref
        )
      )

      eventually {
        (Task.sleep(500.millis) >> Task.delay { supervisor ! Stop(None) }).runSyncUnsafe()
        list should not be empty
        ref.get.runSyncUnsafe() shouldEqual true
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

      val finalize = UIO { finalizeHappened = true }

      val supervisor = testKit.spawn(
        StreamSupervisorBehavior(
          "streamName",
          Task { stream },
          RetryStrategy.alwaysGiveUp(onError),
          finalize,
          Ref.of[Task, Boolean](false).runSyncUnsafe()
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
        StreamSupervisorBehavior(
          "streamName",
          Task { stream },
          RetryStrategy.alwaysGiveUp(onError),
          UIO { finalizeHappened = true },
          Ref.of[Task, Boolean](false).runSyncUnsafe()
        )
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
        StreamSupervisorBehavior(
          "streamName",
          Task { stream },
          RetryStrategy.constant(20.millis, 3, _ => true, onError),
          UIO { numberOfRetries += 1 },
          Ref.of[Task, Boolean](false).runSyncUnsafe()
        )
      )

      eventually {
        list should not be empty
        numberOfRetries shouldBe 4
      }
    }

    "restart the stream if an evaluation fails as long as the maxRetries in the retry policy doesn't apply" in {
      var numberOfRetries = 0

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task.raiseWhen(l == 3L)(new Exception("Boom !!!"))
      }

      testKit.spawn(
        StreamSupervisorBehavior(
          "streamName",
          Task { stream },
          RetryStrategy.constant(20.millis, 3, _ => true, onError),
          UIO { numberOfRetries += 1 },
          Ref.of[Task, Boolean](false).runSyncUnsafe()
        )
      )

      eventually {
        numberOfRetries shouldBe 4
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
        StreamSupervisorBehavior(
          "streamName",
          Task { stream },
          RetryStrategy.constant(20.millis, 3, retryWhen, onError),
          UIO { numberOfRetries += 1 },
          Ref.of[Task, Boolean](false).runSyncUnsafe()
        )
      )

      eventually {
        list should not be empty
        numberOfRetries shouldBe 2
      }
    }
  }
}
