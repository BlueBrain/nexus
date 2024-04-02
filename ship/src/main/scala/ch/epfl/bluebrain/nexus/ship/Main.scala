package ch.epfl.bluebrain.nexus.ship

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.ship.BuildInfo
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.Path

object Main
    extends CommandIOApp(
      name = "ship",
      header = "Nexus Ship",
      version = BuildInfo.version
    ) {

  private val logger = Logger[Main.type]

  private val inputFile: Opts[Path] =
    Opts.option[String]("file", help = "The data file containing the imports.").map(Path(_))

  private val configFile: Opts[Option[Path]] =
    Opts.option[String]("config", help = "The configuration file.").map(Path(_)).orNone

  private val offset: Opts[Offset] = Opts
    .option[Long]("offset", help = "To perform an incremental import from the provided offset.")
    .map(Offset.at)
    .withDefault(Offset.start)

  private val run = Opts.subcommand("run", "Run an import") {
    (inputFile, configFile, offset).mapN(Run)
  }

  private val showConfig = Opts.subcommand("config", "Show reconciled config") {
    configFile.map(ShowConfig)
  }

  override def main: Opts[IO[ExitCode]] =
    (run orElse showConfig)
      .map {
        case Run(file, config, offset) => new RunShip().run(file, config, offset)
        case ShowConfig(config)        => showConfig(config)
      }
      .map(_.as(ExitCode.Success))

  private[ship] def showConfig(config: Option[Path]) =
    for {
      _      <- logger.info(s"Showing reconciled config")
      config <- ShipConfig.merge(config).map(_._2)
      _      <- logger.info(config.root().render())
    } yield ()

  sealed private trait Command

  final private case class Run(file: Path, config: Option[Path], offset: Offset) extends Command

  final private case class ShowConfig(config: Option[Path]) extends Command

}
