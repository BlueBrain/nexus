package ch.epfl.bluebrain.nexus.cli

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.Wiring.wireOne
import ch.epfl.bluebrain.nexus.cli.modules.config.Config
import ch.epfl.bluebrain.nexus.cli.modules.influx.Influx
import ch.epfl.bluebrain.nexus.cli.modules.postgres.Postgres
import com.monovore.decline.{Command, Help}
import distage.TagK

/**
  * Entrypoint to the application.
  *
  * @param subcommands a set of commands the application can expose
  * @param console     implementation of `Console` to use to print help message
  */
final class Cli[F[_]](
    subcommands: NonEmptyList[AbstractCommand[F]],
    console: Console[F]
)(implicit F: Sync[F]) {

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
      subcommands.reduceMap(_.subcommand)
    }.parse(args, env)
      .fold(help => printHelp(help), identity)
      .recoverWith {
        case err: CliError => console.println(err.show).as(ExitCode.Error)
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
    new Cli[F](
      NonEmptyList.of(
        Config[F](wireOne),
        Postgres[F](wireOne),
        Influx[F](wireOne)
      ),
      Console[F]
    ).command(args, env)
  }
  // $COVERAGE-ON$
}
