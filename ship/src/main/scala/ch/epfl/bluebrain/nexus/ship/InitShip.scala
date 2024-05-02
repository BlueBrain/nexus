package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig

object InitShip {

  def configAndStream(
      run: RunCommand,
      defaultConfig: ShipConfig,
      s3Client: S3StorageClient
  ): IO[(ShipConfig, fs2.Stream[IO, RowEvent])] = {
    run.mode match {
      case RunMode.Local =>
        val eventsStream = EventStreamer.localStreamer.stream(run.path, run.offset)
        ShipConfig.load(run.config).map((_, eventsStream))
      case RunMode.S3    =>
        val shipConfig = run.config match {
          case Some(configPath) =>
            ShipConfig.loadFromS3(s3Client, defaultConfig.s3.importBucket, configPath)
          case None             => IO.pure(defaultConfig)
        }

        for {
          cfg        <- shipConfig
          eventStream = EventStreamer
                          .s3eventStreamer(s3Client, cfg.s3.importBucket)
                          .stream(run.path, run.offset)
        } yield (cfg, eventStream)
    }
  }
}
