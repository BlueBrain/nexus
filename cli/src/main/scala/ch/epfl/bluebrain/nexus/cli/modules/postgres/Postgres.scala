package ch.epfl.bluebrain.nexus.cli.modules.postgres

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.AbstractCommand
import com.monovore.decline.Opts
import distage.TagK
import izumi.distage.model.recursive.LocatorRef

/**
  * CLI postgres specific options.
  */
final class Postgres[F[_]: Timer: Parallel: ContextShift: TagK](locatorOpt: Option[LocatorRef])(implicit
    F: ConcurrentEffect[F]
) extends AbstractCommand[F](locatorOpt) {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("postgres", "Postgres database projection.") {
      run
    }

  def run: Opts[F[ExitCode]] =
    Opts.subcommand("run", "Runs the postgres database projection") {
      locatorResource.map { _.use { locator => locator.get[PostgresProjection[F]].run.as(ExitCode.Success) } }
    }

}

object Postgres {

  final def apply[F[_]: TagK: ConcurrentEffect: Timer: Parallel: ContextShift](
      locatorOpt: Option[LocatorRef] = None
  ): Postgres[F] =
    new Postgres[F](locatorOpt)

}
