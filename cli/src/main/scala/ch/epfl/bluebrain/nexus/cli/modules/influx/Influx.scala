package ch.epfl.bluebrain.nexus.cli.modules.influx

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.AbstractCommand
import com.monovore.decline.Opts
import distage.TagK
import izumi.distage.model.recursive.LocatorRef

/**
  * CLI InfluxDB specific options.
  */
final class Influx[F[_]: Timer: Parallel: ContextShift: TagK](locatorOpt: Option[LocatorRef])(implicit
    F: ConcurrentEffect[F]
) extends AbstractCommand[F](locatorOpt) {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("influxdb", "influxDB projection.") {
      run
    }

  def run: Opts[F[ExitCode]] =
    Opts.subcommand("run", "Runs the influxDB projection") {
      locatorResource.map { _.use { locator => locator.get[InfluxProjection[F]].run.as(ExitCode.Success) } }
    }

}

object Influx {

  final def apply[F[_]: TagK: ConcurrentEffect: Timer: Parallel: ContextShift](
      locatorOpt: Option[LocatorRef] = None
  ): Influx[F] =
    new Influx[F](locatorOpt)

}
