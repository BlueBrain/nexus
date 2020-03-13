package ch.epfl.bluebrain.nexus.cli.postgres.cli

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.error.CliError
import ch.epfl.bluebrain.nexus.cli.postgres.PostgresModule
import ch.epfl.bluebrain.nexus.cli.{Console, EffectModule, SharedModule}
import com.github.ghik.silencer.silent
import com.monovore.decline.{Command, Help}
import distage.{Injector, TagK}
import izumi.distage.model.definition.Activation
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.plan.GCMode

/**
  * Entrypoint to the application.
  *
  * @param console a console reference to use for printing errors and help messages
  */
class Cli[F[_]](console: Console[F])(implicit F: Sync[F]) {
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
    Command("nexus-postgres", "Nexus PostgreSQL projection CLI") {
      Config[F](console).subcommand
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
  @silent
  // $COVERAGE-OFF$
  def apply[F[_]: Parallel: ContextShift: ConcurrentEffect: Timer: TagK](
      args: List[String],
      env: Map[String, String] = Map.empty
  ): F[ExitCode] = {
    val effects  = EffectModule[F]
    val shared   = SharedModule[F]
    val postgres = PostgresModule[F]
    val modules  = effects ++ shared ++ postgres
    Injector(Activation(Repo -> Repo.Prod)).produceF[F](modules, GCMode.NoGC).use { locator =>
      locator.get[Cli[F]].command(args, env)
    }
  }
}
