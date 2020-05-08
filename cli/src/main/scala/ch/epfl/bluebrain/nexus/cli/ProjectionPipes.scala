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
  def printConsumedEventSkipFailed[F[_]: Monad, A, E](console: Console[F]): Pipe[F, Either[E, A], A] =
    printSkipFailed(
      console,
      (successes, errors) => s"Read ${successes + errors} events (success: $successes, errors: $errors)"
    )

  /**
    * Print the evaluated projections to the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    *
    * @return it returns the successfully evaluated projections, ignoring the failures
    */
  def printEvaluatedProjectionSkipFailed[F[_]: Monad, A, E](console: Console[F]): Pipe[F, Either[E, A], A] =
    printSkipFailed(
      console,
      (successes, errors) => s"Processed ${successes + errors} events (success: $successes, errors: $errors)"
    )

  private[cli] def printSkipFailed[F[_], A, E](console: Console[F], line: (Long, Long) => String)(
      implicit F: Monad[F]
  ): Pipe[F, Either[E, A], A] =
    _.mapAccumulate((0L, 0L)) {
      case ((successes, errors), v @ Right(_)) => ((successes + 1, errors), v)
      case ((successes, errors), v @ Left(_))  => ((successes, errors + 1), v)
    }.evalMap {
        case ((successes, errors), v) if (successes + errors) % 100 == 0 && (successes + errors) != 0L =>
          console.println(line(successes, errors)) >> F.pure(v)
        case ((_, _), v) => F.pure(v)
      }
      .collect { case Right(v) => v }
}
