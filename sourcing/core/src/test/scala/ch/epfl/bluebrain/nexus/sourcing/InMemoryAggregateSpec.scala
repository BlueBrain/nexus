package ch.epfl.bluebrain.nexus.sourcing

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.sourcing.AggregateFixture._
import ch.epfl.bluebrain.nexus.sourcing.Command.{Increment, IncrementAsync, Initialize}
import ch.epfl.bluebrain.nexus.sourcing.Event.{Incremented, Initialized}
import ch.epfl.bluebrain.nexus.sourcing.State.Current

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class InMemoryAggregateSpec extends SourcingSpec {

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  "An InMemoryAggregate" should {

    val agg = Aggregate
      .inMemory[IO, Int]("global", initialState, AggregateFixture.next, AggregateFixture.evaluate[IO])
      .unsafeRunSync()

    "return its name" in {
      agg.name shouldEqual "global"
    }

    "update its state when accepting commands" in {
      agg.evaluateE(1, Increment(0, 2)).unsafeRunSync().rightValue shouldEqual Incremented(1, 2)
      agg
        .evaluate(1, IncrementAsync(1, 5, 200.millis))
        .unsafeRunSync()
        .rightValue shouldEqual (Current(2, 7) -> Incremented(2, 5))
      agg.currentState(1).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "return its current seq nr" in {
      agg.lastSequenceNr(1).unsafeRunSync() shouldEqual 2L
    }

    "test without applying changes" in {
      agg.test(1, Initialize(0)).unsafeRunSync().leftValue
      agg.testE(1, Initialize(2)).unsafeRunSync().rightValue shouldEqual Initialized(3)
      agg.testS(1, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      agg.currentState(1).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "not update its state if evaluation fails" in {
      agg.evaluate(1, Initialize(0)).unsafeRunSync().leftValue
      agg.currentState(1).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "evaluate commands one at a time" in {
      agg.evaluateS(1, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      agg.currentState(1).unsafeRunSync() shouldEqual Current(3, 0)
      agg.evaluateS(1, IncrementAsync(3, 2, 300.millis)).unsafeToFuture()
      agg.evaluateE(1, IncrementAsync(4, 2, 20.millis)).unsafeRunSync().rightValue shouldEqual Incremented(5, 2)
      agg.currentState(1).unsafeRunSync() shouldEqual Current(5, 4)
    }

    "fold over the event stream in order" in {
      agg
        .foldLeft(1, (0, true)) {
          case ((lastRev, succeeded), event) => (event.rev, succeeded && event.rev - lastRev == 1)
        }
        .unsafeRunSync()
        ._2 shouldEqual true
    }

    "return all events" in {
      agg.foldLeft(1, 0) { case (acc, _) => acc + 1 }.unsafeRunSync() shouldEqual 5
    }

    "append events" in {
      agg.append(2, Incremented(1, 2)).unsafeRunSync() shouldEqual 1L
      agg.currentState(1).unsafeRunSync() shouldEqual Current(5, 4)
    }

    "return true for existing ids" in {
      agg.exists(1).unsafeRunSync() shouldEqual true
    }

    "return false for unknown ids" in {
      agg.exists(Int.MaxValue).unsafeRunSync() shouldEqual false
    }

    "return the sequence number for a snapshot" in {
      agg.snapshot(1).unsafeRunSync() shouldEqual 5L
    }

  }

}
