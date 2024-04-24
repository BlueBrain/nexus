package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig

object InitShip {

  def configAndStream(
      run: RunCommand,
      s3Client: S3StorageClient
  ): IO[(ShipConfig, fs2.Stream[IO, RowEvent])] = {
    ShipConfig.load(run.config).flatMap { shipConfig =>
      run.mode match {
        case RunMode.Local =>
          val eventsStream = EventStreamer.localStreamer.stream(run.path, run.offset)
          ShipConfig.load(run.config).map((_, eventsStream))
        case RunMode.S3    =>
          val eventsStream =
            EventStreamer.s3eventStreamer(s3Client, shipConfig.s3.importBucket).stream(run.path, run.offset)
          val config       = run.config match {
            case Some(configPath) => ShipConfig.loadFromS3(s3Client, shipConfig.s3.importBucket, configPath)
            case None             => IO.pure(shipConfig)
          }
          config.map((_, eventsStream))
      }
    }
  }
}
