package ch.epfl.bluebrain.nexus.cli.modules.postgres

import cats.effect.{ExitCode, Resource, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.AbstractCommand
import com.monovore.decline.Opts

/**
  * CLI postgres specific options.
  */
final class Postgres[F[_]: Sync](postgresProjection: Opts[Resource[F, PostgresProjection[F]]])
    extends AbstractCommand[F] {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("postgres", "Postgres database projection.") {
      run
    }

  def run: Opts[F[ExitCode]] =
    Opts.subcommand("run", "Runs the postgres database projection") {
      postgresProjection.map(_.use(_.run.as(ExitCode.Success)))
    }

}

object Postgres {

  final def apply[F[_]: Sync](postgresProjection: Opts[Resource[F, PostgresProjection[F]]]): Postgres[F] =
    new Postgres(postgresProjection)

}
