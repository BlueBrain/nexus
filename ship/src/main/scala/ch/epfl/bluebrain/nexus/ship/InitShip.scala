package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

object InitShip {

  def apply(run: RunCommand): IO[(ShipConfig, fs2.Stream[IO, RowEvent])] = run.mode match {
    case RunMode.Local =>
      val eventsStream = EventStreamer.localStreamer.stream(run.path, run.offset)
      ShipConfig.load(run.config).map(_ -> eventsStream)
    case RunMode.S3    =>
      for {
        localConfig            <- ShipConfig.load(None)
        s3Config                = localConfig.s3
        (config, eventsStream) <-
          S3StorageClient.resource(s3Config.endpoint, DefaultCredentialsProvider.create()).use { client =>
            val eventsStream = EventStreamer.s3eventStreamer(client, s3Config.importBucket).stream(run.path, run.offset)
            val config       = run.config match {
              case Some(configPath) => ShipConfig.loadFromS3(client, s3Config.importBucket, configPath)
              case None             => IO.pure(localConfig)
            }
            config.map(_ -> eventsStream)
          }
      } yield (config, eventsStream)
  }

}
