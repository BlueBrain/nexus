package ch.epfl.bluebrain.nexus.ship

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.ship.BuildInfo
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.Path
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

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

  private val s3: Opts[Boolean] =
    Opts.flag("s3", help = "Run the import from an S3 bucket").orFalse

  private val run = Opts.subcommand("run", "Run an import") {
    (inputPath, configFile, offset, s3).mapN(Run)
  }

  private val showConfig = Opts.subcommand("config", "Show reconciled config") {
    configFile.map(ShowConfig)
  }

  override def main: Opts[IO[ExitCode]] =
    (run orElse showConfig)
      .map {
        case Run(path, config, offset, false) =>
          RunShip.localShip.run(path, config, offset)
        case Run(path, config, offset, true)  =>
          ShipConfig.load(config).flatMap { cfg =>
            s3client(cfg).use { client =>
              RunShip.s3Ship(client, cfg.S3.importBucket).run(path, config, offset)
            }
          }
        case ShowConfig(config)               => showConfig(config)
      }
      .map(_.as(ExitCode.Success))

  private[ship] def showConfig(config: Option[Path]) =
    for {
      _      <- logger.info(s"Showing reconciled config")
      config <- ShipConfig.merge(config).map(_._2)
      _      <- logger.info(config.root().render())
    } yield ()

  private def s3client(config: ShipConfig) =
    S3StorageClient.resource(config.S3.endpoint, DefaultCredentialsProvider.create())

  sealed private trait Command

  final private case class Run(path: Path, config: Option[Path], offset: Offset, s3: Boolean) extends Command

  final private case class ShowConfig(config: Option[Path]) extends Command

}
