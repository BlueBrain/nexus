package ch.epfl.bluebrain.nexus.cli.postgres.cli

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Sync, Timer}
import cats.implicits._
import com.github.ghik.silencer.silent
import com.monovore.decline.{Command, Help}

object Cli {

  private def printHelp[F[_]](help: Help)(implicit F: Sync[F]): F[ExitCode] =
    F.delay(System.err.println(help)).as {
      if (help.errors.nonEmpty) ExitCode.Error
      else ExitCode.Success
    }

  /**
    * Execute the application by evaluating the intent and config from the command line arguments.
    *
    * @param args the command line arguments to evaluate
    * @param env  the application environment
    */
  @silent
  def apply[F[_]: ContextShift: ConcurrentEffect: Timer](
      args: List[String],
      env: Map[String, String] = Map.empty
  ): F[ExitCode] =
    Command("nexus-postgres", "Nexus PostgreSQL projection CLI") {
      Config[F].subcommand
    }.parse(args, env)
      .fold(printHelp[F], identity)

}
