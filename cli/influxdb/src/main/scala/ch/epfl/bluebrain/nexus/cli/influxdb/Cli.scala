package ch.epfl.bluebrain.nexus.cli.influxdb

import cats.Parallel
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, ExitCode, Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.error.CliError
import ch.epfl.bluebrain.nexus.cli.influxdb.cli.InfluxDb
import com.monovore.decline.{Command, Help}
import distage.TagK

/**
  * Entrypoint to the application.
  *
  */
class Cli[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK](implicit F: Sync[F]) {
  private def printHelp(help: Help): F[ExitCode] =
    F.delay(println(help.toString())).as {
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
    Command("nexus-influxdb", "Nexus InfluxDB projection CLI") {
      InfluxDb[F].subcommand
    }.parse(args, env)
      .fold(help => printHelp(help), identity)
      .recoverWith {
        case err: CliError => F.delay(println(err.show)).as(ExitCode.Error)
      }
}

object Cli {
  def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK]: Cli[F] = {
    new Cli[F]()
  }
}
