package ch.epfl.bluebrain.nexus.cli

import cats.Monad
import cats.implicits._
import fs2.Pipe

object ProjectionPipes {

  /**
    * Print the consumed events to the passed ''console''. It prints every 100 consumed events.
    */
  def printConsumedEvent[F[_], A](console: Console[F])(implicit F: Monad[F]): Pipe[F, A, A] =
    _.mapAccumulate(0L)((idx, tuple) => (idx + 1, tuple))
      .evalMap {
        case (idx, tuple) if idx % 100 == 0 && idx != 0L =>
          console.println(s"Read $idx events.") >> F.pure(tuple)
        case (_, tuple) => F.pure(tuple)
      }

  /**
    * Print the evaluated projections to the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    */
  def printEvaluatedProjection[F[_], A](
      console: Console[F]
  )(implicit F: Monad[F]): Pipe[F, ClientErrOr[A], ClientErrOr[A]] =
    _.mapAccumulate((0L, 0L)) {
      case ((successes, errors), v @ Right(_)) => ((successes + 1, errors), v)
      case ((successes, errors), v @ Left(_))  => ((successes, errors + 1), v)
    }.evalMap {
      case ((successes, errors), v) if (successes + errors) % 100 == 0 && (successes + errors) != 0L =>
        console.println(s"Processed ${successes + errors} events (success: $successes, errors: $errors)") >>
          F.pure(v)
      case ((_, _), v) => F.pure(v)
    }
}
