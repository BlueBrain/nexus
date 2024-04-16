package ch.epfl.bluebrain.nexus.ship.config

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.config.Configs
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import com.typesafe.config.Config
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import fs2.io.file.Path
import pureconfig.ConfigReader
import pureconfig.backend.ConfigFactoryWrapper
import pureconfig.error.ConfigReaderException
import pureconfig.generic.semiauto.deriveReader

import java.nio.charset.StandardCharsets.UTF_8

final case class ShipConfig(database: DatabaseConfig, s3: S3Config, input: InputConfig)

object ShipConfig {

  implicit final val shipConfigReader: ConfigReader[ShipConfig] = {
    deriveReader[ShipConfig]
  }

  def merge(externalConfigPath: Option[Path]): IO[(ShipConfig, Config)] = {
    val externalConfig = Configs.parseFile(externalConfigPath.map(_.toNioPath.toFile))
    externalConfig.flatMap(mergeFromConfig)
  }

  def merge(externalConfigStream: Stream[IO, Byte]): IO[(ShipConfig, Config)] = {
    val externalConfig = configFromStream(externalConfigStream)
    externalConfig.flatMap(mergeFromConfig)
  }

  private def mergeFromConfig(externalConfig: Config): IO[(ShipConfig, Config)] =
    for {
      defaultConfig <- Configs.parseResource("ship-default.conf")
      result        <- Configs.merge[ShipConfig]("ship", externalConfig, defaultConfig)
    } yield result

  def load(externalConfigPath: Option[Path]): IO[ShipConfig] =
    merge(externalConfigPath).map(_._1)

  def loadFromS3(client: S3StorageClient, bucket: BucketName, path: Path): IO[ShipConfig] = {
    val configStream = client.readFile(bucket, FileKey(NonEmptyString.unsafeFrom(path.toString)))
    configFromStream(configStream).flatMap(mergeFromConfig)
  }.map(_._1)

  /**
    * Loads a config from a stream. Taken from
    * https://github.com/pureconfig/pureconfig/tree/master/modules/fs2/src/main/scala/pureconfig/module/fs2
    */
  private def configFromStream(configStream: Stream[IO, Byte]): IO[Config] =
    for {
      bytes         <- configStream.compile.to(Array)
      string         = new String(bytes, UTF_8)
      configOrError <- IO.delay(ConfigFactoryWrapper.parseString(string))
      config        <- IO.fromEither(configOrError.leftMap(ConfigReaderException[Config]))
    } yield config

}
