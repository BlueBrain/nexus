package ch.epfl.bluebrain.nexus.sourcing

import cats.effect.IO
import org.scalatest.concurrent.ScalaFutures
import ch.epfl.bluebrain.nexus.sourcing.syntax._
import org.scalatest.EitherValues

class EvaluationSyntaxSpec extends SourcingSpec with ScalaFutures with EitherValues {
  type State   = (Int)
  type Command = Int
  "An evaluation syntax" should {
    "transform a '(state, command) => state' evaluation into a '(state, command) => F(Right(state))'" in {
      val eval: (State, Command) => State = {
        case (st, cmd) => (st + cmd)
      }
      val evalEitherF = eval.toEitherF[IO]
      evalEitherF(2, 3).unsafeRunSync().rightValue shouldEqual 5
    }

    "transform a '(state, command) => F(state)' evaluation into a '(state, command) => F(Right(state))'" in {
      val err = new RuntimeException("error")
      val eval: (State, Command) => IO[State] = {
        case (st, cmd) if st < 0 || cmd < 0 => IO.raiseError(err)
        case (st, cmd)                      => IO.pure(st + cmd)
      }
      val evalEitherF = eval.toEither
      evalEitherF(1, 2).unsafeRunSync().rightValue shouldEqual 3
      evalEitherF(-1, 3).unsafeToFuture().failed.futureValue shouldEqual err
      evalEitherF(1, -3).unsafeToFuture().failed.futureValue shouldEqual err
    }
  }
}
