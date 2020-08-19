package ch.epfl.bluebrain.nexus.cli.modules.literature

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.AbstractCommand
import com.monovore.decline.Opts
import distage.TagK
import izumi.distage.model.recursive.LocatorRef

/**
  * CLI literature specific options.
  */
final class Literature[F[_]: Timer: Parallel: ContextShift: TagK](locatorOpt: Option[LocatorRef])(implicit
    F: ConcurrentEffect[F]
) extends AbstractCommand[F](locatorOpt) {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("literature", "literature extraction projection.") {
      run
    }

  def run: Opts[F[ExitCode]] =
    Opts.subcommand("run", "Runs the literature projection") {
      locatorResource.map { _.use { locator => locator.get[LiteratureProjection[F]].run.as(ExitCode.Success) } }
    }

}

object Literature {

  final def apply[F[_]: TagK: ConcurrentEffect: Timer: Parallel: ContextShift](
      locatorOpt: Option[LocatorRef] = None
  ): Literature[F] =
    new Literature[F](locatorOpt)

}
