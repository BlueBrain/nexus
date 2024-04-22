package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

object InitShip {

  def apply(run: RunCommand): Resource[IO, (ShipConfig, fs2.Stream[IO, RowEvent], S3StorageClient, Transactors)] =
    Resource.eval(configAndStream(run)).flatMap { case (config, eventStream, s3Client) =>
      Transactors
        .init(config.database)
        .map { xas => (config, eventStream, s3Client, xas) }
    }

  private def configAndStream(run: RunCommand): IO[(ShipConfig, fs2.Stream[IO, RowEvent], S3StorageClient)] = {
    ShipConfig.load(run.config).flatMap { shipConfig =>
      S3StorageClient.resource(shipConfig.s3.endpoint, DefaultCredentialsProvider.create()).use { s3Client =>
        run.mode match {
          case RunMode.Local =>
            val eventsStream = EventStreamer.localStreamer.stream(run.path, run.offset)
            ShipConfig.load(run.config).map((_, eventsStream, s3Client))
          case RunMode.S3    =>
            val eventsStream =
              EventStreamer.s3eventStreamer(s3Client, shipConfig.s3.importBucket).stream(run.path, run.offset)
            val config       = run.config match {
              case Some(configPath) => ShipConfig.loadFromS3(s3Client, shipConfig.s3.importBucket, configPath)
              case None             => IO.pure(shipConfig)
            }
            config.map((_, eventsStream, s3Client))
        }
      }
    }
  }
}
