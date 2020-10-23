package ch.epfl.bluebrain.nexus.cli

import cats.Monad
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.PrintConfig
import fs2.Pipe

object ProjectionPipes {

  /**
    * Print the progress of the consumed events through the passed ''console''.
    * It prints every 100 consumed events, distinguishing skip, errors and success events.
    *
    * @return it returns the successful events, ignoring the failures and the skipped
    */
  def printEventProgress[F[_], A, E <: CliError](
      console: Console[F]
  )(implicit F: Monad[F], cfg: PrintConfig): Pipe[F, Either[E, Option[A]], A] =
    _.evalMapAccumulate[F, (Long, Long, Long), Either[E, Option[A]]]((0L, 0L, 0L)) {
      case ((success, skip, errors), v @ Right(Some(_))) =>
        F.pure(((success + 1, skip, errors), v))
      case ((success, skip, errors), v @ Right(None))    =>
        F.pure(((success, skip + 1, errors), v))
      case ((success, skip, errors), v @ Left(err))      =>
        console.printlnErr(err.asString).as(((success, skip, errors + 1), v))
    }.evalMap {
      case ((success, skip, errors), v)
          if cfg.progressInterval
            .exists(interval => (success + skip + errors) % interval == 0) && (success + skip + errors) != 0L =>
        console
          .println(s"Read ${success + skip + errors} events (success: $success, skip: $skip, errors: $errors)")
          .as(v)
      case ((_, _, _), v) => F.pure(v)
    }.collect { case Right(Some(v)) => v }

  /**
    * Print the progress of the evaluated projection through the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    *
    * @return it returns the successfully evaluated projections, ignoring the failures
    */
  def printProjectionProgress[F[_], A, E <: CliError](
      console: Console[F]
  )(implicit F: Monad[F], cfg: PrintConfig): Pipe[F, Either[E, A], A]         =
    _.evalMapAccumulate[F, (Long, Long), Either[E, A]]((0L, 0L)) {
      case ((success, errors), v @ Right(_))  =>
        F.pure(((success + 1, errors), v))
      case ((success, errors), v @ Left(err)) =>
        console.printlnErr(err.asString).as(((success, errors + 1), v))
    }.evalMap {
      case ((successes, errors), v)
          if cfg.progressInterval
            .exists(interval => (successes + errors) % interval == 0) && (successes + errors) != 0L =>
        console.println(s"Processed ${successes + errors} events (success: $successes, errors: $errors)").as(v)
      case ((_, _), v) => F.pure(v)
    }.collect { case Right(v) => v }
}
