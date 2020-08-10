package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcingnew.projections.StreamSupervisor.Stop
import fs2.Stream
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class StreamSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Eventually {

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  "Start and stop the StreamSupervisor and its stream" should {

    "work fine if we send a stop message" in {
      var list = List.empty[Long]
      var finalizeHappened = false

      val stream = Stream.repeatEval {
          IO {
            list = list.appended(System.currentTimeMillis)
          }
        }.metered(20.millis)

      val supervisor = testKit.spawn(
        StreamSupervisor.behavior(
          IO { stream },
          RetryStrategy.alwaysGiveUp[IO],
          Some(IO { finalizeHappened = true })
        )
      )

      (IO.sleep(60.millis) >> IO.delay { supervisor ! Stop }).unsafeRunAsyncAndForget()

      eventually {
        list should not be empty
        finalizeHappened shouldBe true
      }
    }

    "work fine if the actor gets stopped " in {
      var list = List.empty[Long]
      var finalizeHappened = false

      val stream = Stream.repeatEval {
        IO { list = list.appended(System.currentTimeMillis) }
      }.metered(20.millis)

      val finalize = IO { finalizeHappened = true }

      val supervisor = testKit.spawn(
        StreamSupervisor.behavior(
          IO { stream },
          RetryStrategy.alwaysGiveUp[IO],
          Some(finalize)
        )
      )

      (IO.sleep(60.millis) >> IO.delay { testKit.stop(supervisor) }).unsafeRunAsyncAndForget()

      eventually {
        list should not be empty
        finalizeHappened shouldBe true
      }
    }
  }

  "Retry policy" should {
    "stop the stream if the stream fails and the retry policy doesn't apply" in {
      var list = List.empty[Long]
      var finalizeHappened = false

      val stream = Stream(1L,2L,3L).evalMap {
        l => IO { list = list.appended(l) }
      } ++ Stream.raiseError[IO] {
        new Exception("Oops, something went wrong")
      }

      testKit.spawn(StreamSupervisor.behavior(
        IO { stream },
        RetryStrategy.alwaysGiveUp[IO],
        Some(IO { finalizeHappened = true }))
      )

      eventually {
        list should not be empty
        finalizeHappened shouldBe true
      }
    }

    "restart the stream if the stream fails as long as the maxRetries in the retry policy doesn't apply" in {
      var list = List.empty[Long]
      var numberOfRetries = 0

      val stream = Stream(1L,2L,3L).evalMap {
        l => IO { list = list.appended(l) }
      } ++ Stream.raiseError[IO] {
        new Exception("Oops, something went wrong")
      }

      testKit.spawn(StreamSupervisor.behavior(
        IO { stream },
        RetryStrategy.constant[IO](20.millis, 3, _ => true),
        Some(IO { numberOfRetries += 1 }))
      )

      eventually {
        list should not be empty
        numberOfRetries shouldBe 3
      }
    }

    "stop the stream if the error doesn't satisfy the predicate for a retry" in {
      var list = List.empty[Long]
      var numberOfRetries = 0

      val stream = Stream(1L,2L,3L).evalMap {
        l => IO { list = list.appended(l) }
      } ++ Stream.raiseError[IO] {
        numberOfRetries match {
          case 0 => new IllegalArgumentException("Fail")
          case _ => new NullPointerException("Fail")
        }
      }

      def retryWhen(t: Throwable): Boolean = t match {
        case _: IllegalArgumentException => true
        case _ => false
      }

      testKit.spawn(StreamSupervisor.behavior(
        IO { stream },
        RetryStrategy.constant[IO](
          20.millis,
          3,
          retryWhen),
          Some(IO { numberOfRetries += 1 })
        )
      )

      eventually {
        list should not be empty
        numberOfRetries shouldBe 2
      }
    }
  }

}
