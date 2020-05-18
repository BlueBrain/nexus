package ch.epfl.bluebrain.nexus.sourcing

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.sourcing.Command.{Increment, IncrementAsync, Initialize}
import ch.epfl.bluebrain.nexus.sourcing.State.Current
import ch.epfl.bluebrain.nexus.sourcing.StateMachineFixture._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class InMemoryStateMachineSpec extends SourcingSpec {

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  "An InMemoryStateMachine" should {

    val cache = StateMachine.inMemory[IO, Int]("global", initialState, evaluate[IO]).unsafeRunSync()

    "return its name" in {
      cache.name shouldEqual "global"
    }

    "update its state when accepting commands" in {
      cache.evaluate(1, Increment(0, 2)).unsafeRunSync().rightValue shouldEqual Current(1, 2)
      cache.evaluate(1, IncrementAsync(1, 5, 200.millis)).unsafeRunSync().rightValue shouldEqual Current(2, 7)
      cache.currentState(1).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "test without applying changes" in {
      cache.test(1, Initialize(0)).unsafeRunSync().leftValue
      cache.test(1, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      cache.test(1, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      cache.currentState(1).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "not update its state if evaluation fails" in {
      cache.evaluate(1, Initialize(0)).unsafeRunSync().leftValue
      cache.currentState(1).unsafeRunSync() shouldEqual Current(2, 7)
    }

    "evaluate commands one at a time" in {
      cache.evaluate(1, Initialize(2)).unsafeRunSync().rightValue shouldEqual Current(3, 0)
      cache.currentState(1).unsafeRunSync() shouldEqual Current(3, 0)
      cache.evaluate(1, IncrementAsync(3, 2, 300.millis)).unsafeToFuture()
      cache.evaluate(1, IncrementAsync(4, 2, 20.millis)).unsafeRunSync().rightValue shouldEqual Current(5, 4)
      cache.currentState(1).unsafeRunSync() shouldEqual Current(5, 4)
    }

  }

}
