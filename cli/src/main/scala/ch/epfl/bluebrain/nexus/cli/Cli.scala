package ch.epfl.bluebrain.nexus.cli

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.modules.config.Config
import ch.epfl.bluebrain.nexus.cli.modules.postgres.Postgres
import ch.epfl.bluebrain.nexus.cli.modules.influx.Influx
import com.monovore.decline.{Command, Help}
import distage.{LocatorRef, TagK}

/**
  * Entrypoint to the application.
  *
  * @param locatorOpt an optional locator instance to be cascaded to all commands
  */
class Cli[F[_]: TagK: Parallel: ContextShift: Timer](locatorOpt: Option[LocatorRef])(implicit F: ConcurrentEffect[F]) {

  private def console: Console[F] =
    locatorOpt match {
      case Some(value) => value.get.get[Console[F]]
      case None        => Console[F]
    }

  private def printHelp(help: Help): F[ExitCode] =
    console.println(help.toString()).as {
      if (help.errors.nonEmpty) ExitCode.Error
      else ExitCode.Success
    }

  /**
    * Execute the application by evaluating the intent and config from the command line arguments.
    *
    * @param args the command line arguments to evaluate
    * @param env  the application environment
    */
  def command(args: List[String], env: Map[String, String] = Map.empty): F[ExitCode] =
    Command("nexus-cli", "Nexus CLI") {
      Config[F](locatorOpt).subcommand orElse Postgres[F](locatorOpt).subcommand orElse Influx[F](locatorOpt).subcommand
    }.parse(args, env)
      .fold(help => printHelp(help), identity)
      .recoverWith { case err: CliError =>
        console.println(err.show).as(ExitCode.Error)
      }
}

object Cli {

  /**
    * Execute the application by evaluating the intent and config from the command line arguments.
    *
    * @param args the command line arguments to evaluate
    * @param env  the application environment
    */
  // $COVERAGE-OFF$
  def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK](
      args: List[String],
      env: Map[String, String]
  ): F[ExitCode] = {
    new Cli[F](None).command(args, env)
  }
  // $COVERAGE-ON$
}
