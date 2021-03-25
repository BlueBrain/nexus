package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamBehaviour.{Stop, SupervisorCommand}
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike
import retry.RetryDetails

import scala.concurrent.duration._

class DaemonStreamSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Eventually {

  import monix.execution.Scheduler.Implicits.global
  implicit val uuidF: UUIDF = UUIDF.random

  "Start and stop the StreamSupervisor and its stream" should {

    "work fine if we send a stop message" in {
      val testProbe                                                 = testKit.createTestProbe[SupervisorCommand]()
      var list                                                      = List.empty[Long]
      var finalizeHappened                                          = false
      val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => Task.unit

      val stream     = Stream
        .repeatEval {
          Task {
            list = list.appended(System.currentTimeMillis)
          }
        }
        .metered(20.millis)
        .onFinalize(UIO { finalizeHappened = true })
      val supervisor = testKit.spawn(
        DaemonStreamBehaviour(
          "streamName",
          stream,
          RetryStrategy.alwaysGiveUp(onError)
        )
      )

      eventually {
        (Task.sleep(500.millis) >> Task.delay { supervisor ! Stop(None) }).runSyncUnsafe()
        list should not be empty
        testProbe.expectTerminated(supervisor)
        finalizeHappened shouldBe true
      }
    }

    "work fine if the actor gets stopped " in {
      val testProbe                                                 = testKit.createTestProbe[SupervisorCommand]()
      var list                                                      = List.empty[Long]
      var finalizeHappened                                          = false
      val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => Task.unit

      val stream = Stream
        .repeatEval {
          Task { list = list.appended(System.currentTimeMillis) }
        }
        .metered(20.millis)
        .onFinalize(UIO { finalizeHappened = true })

      val supervisor = testKit.spawn(
        DaemonStreamBehaviour(
          "streamName",
          stream,
          RetryStrategy.alwaysGiveUp(onError)
        )
      )

      eventually {
        (Task.sleep(60.millis) >> Task.delay { testKit.stop(supervisor) }).runSyncUnsafe()
        list should not be empty
        testProbe.expectTerminated(supervisor)
        finalizeHappened shouldBe true
      }
    }
  }

  "Retry policy" should {
    "stop the stream if the stream fails and the retry policy doesn't apply" in {
      val testProbe                                                 = testKit.createTestProbe[SupervisorCommand]()
      var list                                                      = List.empty[Long]
      var finalizeHappened                                          = false
      val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => Task.unit

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task { list = list.appended(l) }
      } ++ Stream
        .raiseError[Task] {
          new Exception("Oops, something went wrong")
        }
        .onFinalize(UIO { finalizeHappened = true })

      val supervisor = testKit.spawn(
        DaemonStreamBehaviour(
          "streamName",
          stream,
          RetryStrategy.alwaysGiveUp(onError)
        )
      )

      eventually {
        list should not be empty
        testProbe.expectTerminated(supervisor)
        finalizeHappened shouldBe true
      }
    }

    "restart the stream if the stream fails as long as the maxRetries in the retry policy doesn't apply" in {
      val testProbe                                                 = testKit.createTestProbe[SupervisorCommand]()
      var list                                                      = List.empty[Long]
      var numberOfRetries                                           = 0
      val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => UIO { numberOfRetries += 1 }

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task { list = list.appended(l) }
      } ++ Stream.raiseError[Task] {
        new Exception("Oops, something went wrong")
      }

      val supervisor = testKit.spawn(
        DaemonStreamBehaviour(
          "streamName",
          stream,
          RetryStrategy.constant(20.millis, 3, _ => true, onError)
        )
      )

      eventually {
        list should not be empty
        testProbe.expectTerminated(supervisor)
        numberOfRetries shouldBe 4
      }
    }

    "restart the stream if an evaluation fails as long as the maxRetries in the retry policy doesn't apply" in {
      val testProbe                                                 = testKit.createTestProbe[SupervisorCommand]()
      var numberOfRetries                                           = 0
      val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => UIO { numberOfRetries += 1 }

      val stream = Stream(1L, 2L, 3L).evalMap { l =>
        Task.raiseWhen(l == 3L)(new Exception("Boom !!!"))
      }

      val supervisor = testKit.spawn(
        DaemonStreamBehaviour(
          "streamName",
          stream,
          RetryStrategy.constant(20.millis, 3, _ => true, onError)
        )
      )

      eventually {
        numberOfRetries shouldBe 4
        testProbe.expectTerminated(supervisor)
      }
    }

    "stop the stream if the error doesn't satisfy the predicate for a retry" in {
      val testProbe                                                 = testKit.createTestProbe[SupervisorCommand]()
      var list                                                      = List.empty[Long]
      var numberOfRetries                                           = 0
      val onError: (Throwable, RetryDetails) => IO[Throwable, Unit] = (_, _) => UIO { numberOfRetries += 1 }

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

      val supervisor = testKit.spawn(
        DaemonStreamBehaviour(
          "streamName",
          stream,
          RetryStrategy.constant(20.millis, 3, retryWhen, onError)
        )
      )

      eventually {
        list should not be empty
        testProbe.expectTerminated(supervisor)
        numberOfRetries shouldBe 1
      }
    }
  }
}
