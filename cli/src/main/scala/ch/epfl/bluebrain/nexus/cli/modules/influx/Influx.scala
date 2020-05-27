package ch.epfl.bluebrain.nexus.cli.modules.influx

import cats.effect.{ExitCode, Resource, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.AbstractCommand
import com.monovore.decline.Opts

/**
  * CLI InfluxDB specific options.
  */
final class Influx[F[_]: Sync](influxProjection: Opts[Resource[F, InfluxProjection[F]]]) extends AbstractCommand[F] {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("influxdb", "influxDB projection.") {
      run
    }

  def run: Opts[F[ExitCode]] =
    Opts.subcommand("run", "Runs the influxDB projection") {
      influxProjection.map(_.use(_.run.as(ExitCode.Success)))
    }

}

object Influx {

  final def apply[F[_]: Sync](influxProjection: Opts[Resource[F, InfluxProjection[F]]]): Influx[F] =
    new Influx(influxProjection)

}
