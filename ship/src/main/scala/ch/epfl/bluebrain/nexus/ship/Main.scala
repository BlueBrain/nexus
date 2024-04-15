package ch.epfl.bluebrain.nexus.ship

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.ship.BuildInfo
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.ShipCommand._
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

  private val inputPath: Opts[Path] =
    Opts
      .option[String]("path", help = "The path containing the imports. Either a single file or a directory")
      .map(Path(_))

  private val configFile: Opts[Option[Path]] =
    Opts.option[String]("config", help = "The configuration file.").map(Path(_)).orNone

  private val offset: Opts[Offset] = Opts
    .option[Long]("offset", help = "To perform an incremental import from the provided offset.")
    .map(Offset.at)
    .withDefault(Offset.start)

  private val runMode: Opts[RunMode] =
    Opts.flag("s3", help = "Run the import from an S3 bucket").orFalse.map {
      case true  => RunMode.S3
      case false => RunMode.Local
    }

  private val run = Opts.subcommand("run", "Run an import") {
    (inputPath, configFile, offset, runMode).mapN(RunCommand)
  }

  private val showConfig = Opts.subcommand("config", "Show reconciled config") {
    configFile.map(ShowConfigCommand)
  }

  override def main: Opts[IO[ExitCode]] =
    (run orElse showConfig)
      .map {
        case r: RunCommand             => run(r)
        case ShowConfigCommand(config) => showConfig(config)
      }
      .map(_.as(ExitCode.Success))

  private[ship] def run(r: RunCommand) =
    for {
      (config, eventsStream) <- InitShip(r)
      _                      <- Transactors.init(config.database).use { xas =>
                                  RunShip(eventsStream, config.input, xas)
                                }
    } yield ()

  private[ship] def showConfig(config: Option[Path]) =
    for {
      _      <- logger.info(s"Showing reconciled config")
      config <- ShipConfig.merge(config).map(_._2)
      _      <- logger.info(config.root().render())
    } yield ()
}
