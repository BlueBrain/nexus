package ch.epfl.bluebrain.nexus.cli

import cats.Monad
import cats.implicits._
import fs2.Pipe

object ProjectionPipes {

  /**
    * Print the consumed events to the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    *
    * @return it returns the successful events, ignoring the failures
    */
  def printConsumedEventSkipFailed[F[_], A](console: Console[F])(implicit F: Monad[F]): Pipe[F, ClientErrOr[A], A] =
    _.through(countSuccessAndError)
      .evalMap {
        case ((successes, errors), v) if (successes + errors) % 100 == 0 && (successes + errors) != 0L =>
          console.println(s"Read ${successes + errors} events (success: $successes, errors: $errors)") >>
            F.pure(v)
        case ((_, _), v) => F.pure(v)
      }
      .collect { case Right(v) => v }

  /**
    * Print the evaluated projections to the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    */
  def printEvaluatedProjection[F[_], A](
      console: Console[F]
  )(implicit F: Monad[F]): Pipe[F, ClientErrOr[A], ClientErrOr[A]] =
    _.through(countSuccessAndError).evalMap {
      case ((successes, errors), v) if (successes + errors) % 100 == 0 && (successes + errors) != 0L =>
        console.println(s"Processed ${successes + errors} events (success: $successes, errors: $errors)") >>
          F.pure(v)
      case ((_, _), v) => F.pure(v)
    }

  private def countSuccessAndError[F[_], A]: Pipe[F, ClientErrOr[A], ((Long, Long), ClientErrOr[A])] =
    _.mapAccumulate((0L, 0L)) {
      case ((successes, errors), v @ Right(_)) => ((successes + 1, errors), v)
      case ((successes, errors), v @ Left(_))  => ((successes, errors + 1), v)
    }
}
