package ch.epfl.bluebrain.nexus.cli

import cats.Monad
import cats.implicits._
import fs2.Pipe

object ProjectionPipes {

  /**
    * Print the progress of the consumed events through the passed ''console''.
    * It prints every 100 consumed events, distinguishing skip, errors and success events.
    *
    * @return it returns the successful events, ignoring the failures and the skipped
    */
  def printEventProgress[F[_], A, E](console: Console[F])(implicit F: Monad[F]): Pipe[F, Either[E, Option[A]], A] =
    _.mapAccumulate((0L, 0L, 0L)) {
      case ((success, skip, errors), v @ Right(Some(_))) => ((success + 1, skip, errors), v)
      case ((success, skip, errors), v @ Right(None))    => ((success, skip + 1, errors), v)
      case ((success, skip, errors), v @ Left(_))        => ((success, skip, errors + 1), v)
    }.evalMap {
        case ((success, skip, errors), v) if (success + skip + errors) % 100 == 0 && (success + skip + errors) != 0L =>
          console.println(
            s"Read ${success + skip + errors} events (success: $success, skip: $skip, errors: $errors)"
          ) >>
            F.pure(v)
        case ((_, _, _), v) => F.pure(v)
      }
      .collect { case Right(Some(v)) => v }

  /**
    * Print the progress of the evaluated projection through the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    *
    * @return it returns the successfully evaluated projections, ignoring the failures
    */
  def printProjectionProgress[F[_], A, E](
      console: Console[F]
  )(implicit F: Monad[F]): Pipe[F, Either[E, A], A] =
    _.mapAccumulate((0L, 0L)) {
      case ((successes, errors), v @ Right(_)) => ((successes + 1, errors), v)
      case ((successes, errors), v @ Left(_))  => ((successes, errors + 1), v)
    }.evalMap {
        case ((successes, errors), v) if (successes + errors) % 100 == 0 && (successes + errors) != 0L =>
          console.println(s"Processed ${successes + errors} events (success: $successes, errors: $errors)") >>
            F.pure(v)
        case ((_, _), v) => F.pure(v)
      }
      .collect { case Right(v) => v }
}
