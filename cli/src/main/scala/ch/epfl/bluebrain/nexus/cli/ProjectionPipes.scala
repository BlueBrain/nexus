package ch.epfl.bluebrain.nexus.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}

import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.PrintConfig
import ch.epfl.bluebrain.nexus.cli.sse.Offset
import fs2.Pipe

import scala.jdk.CollectionConverters._

object ProjectionPipes {

  /**
    * Print the progress of the consumed events through the passed ''console''.
    * It prints every 100 consumed events, distinguishing skip, errors and success events.
    *
    * @return it returns the successful events, ignoring the failures and the skipped
    */
  def printEventProgress[F[_], A, E <: CliError](
      console: Console[F],
      errorFile: Path
  )(implicit F: Sync[F], cfg: PrintConfig): Pipe[F, Either[(Offset, E), Option[A]], A] =
    _.evalMapAccumulate[F, (Long, Long, Long), Either[(Offset, E), Option[A]]]((0L, 0L, 0L)) {
      case ((success, skip, errors), v @ Right(Some(_)))   =>
        F.pure(((success + 1, skip, errors), v))
      case ((success, skip, errors), v @ Right(None))      =>
        F.pure(((success, skip + 1, errors), v))
      case ((success, skip, errors), v @ Left((off, err))) =>
        console.printlnErr(s"Error on offset '$off' ${err.asString}").as(((success, skip, errors + 1), v)) >>
          F.delay {
            val openOptions = if (Files.exists(errorFile)) StandardOpenOption.APPEND else StandardOpenOption.CREATE
            Files.write(errorFile, List(off.asString, err.asString).asJava, UTF_8, openOptions)
          } >> F.pure((((success, skip, errors + 1), v)))
    }.evalMap {
        case ((success, skip, errors), v)
            if cfg.progressInterval
              .exists(interval => (success + skip + errors) % interval == 0) && (success + skip + errors) != 0L =>
          console
            .println(s"Read ${success + skip + errors} events (success: $success, skip: $skip, errors: $errors)")
            .as(v)
        case ((_, _, _), v) => F.pure(v)
      }
      .collect { case Right(Some(v)) => v }

  /**
    * Print the progress of the evaluated projection through the passed ''console''.
    * It prints every 100 consumed events, distinguishing errors and success.
    *
    * @return it returns the successfully evaluated projections, ignoring the failures
    */
  def printProjectionProgress[F[_], A, E <: CliError](
      console: Console[F],
      errorFile: Path,
      line: (Long, Long) => String = (success, errors) =>
        s"Processed ${success + errors} events (success: $success, errors: $errors)"
  )(implicit F: Sync[F], cfg: PrintConfig): Pipe[F, Either[(Offset, E), A], A]         =
    _.evalMapAccumulate[F, (Long, Long), Either[(Offset, E), A]]((0L, 0L)) {
      case ((success, errors), v @ Right(_))         =>
        F.pure(((success + 1, errors), v))
      case ((success, errors), v @ Left((off, err))) =>
        console.printlnErr(s"Error on offset '$off' ${err.asString}").as(((success, errors + 1), v)) >>
          F.delay {
            val openOptions = if (Files.exists(errorFile)) StandardOpenOption.APPEND else StandardOpenOption.CREATE
            Files.write(errorFile, List(off.asString, err.asString).asJava, UTF_8, openOptions)
          } >> F.pure(((success, errors + 1), v))
    }.evalMap {
        case ((successes, errors), v)
            if cfg.progressInterval
              .exists(interval => (successes + errors) % interval == 0) && (successes + errors) != 0L =>
          console.println(line(successes, errors)).as(v)
        case ((_, _), v) => F.pure(v)
      }
      .collect { case Right(v) => v }
}
