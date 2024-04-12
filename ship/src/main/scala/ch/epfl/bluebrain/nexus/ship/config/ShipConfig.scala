package ch.epfl.bluebrain.nexus.ship.config

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.config.Configs
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, EventLogConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig.ProjectMapping
import com.typesafe.config.Config
import fs2.Stream
import fs2.io.file.Path
import pureconfig.ConfigReader
import pureconfig.backend.ConfigFactoryWrapper
import pureconfig.configurable.genericMapReader
import pureconfig.error.{CannotConvert, ConfigReaderException}
import pureconfig.generic.semiauto.deriveReader

import java.nio.charset.StandardCharsets.UTF_8

final case class ShipConfig(
    baseUri: BaseUri,
    database: DatabaseConfig,
    S3: S3Config,
    eventLog: EventLogConfig,
    organizations: OrganizationCreationConfig,
    projectMapping: ProjectMapping = Map.empty,
    serviceAccount: ServiceAccountConfig
)

object ShipConfig {

  type ProjectMapping = Map[ProjectRef, ProjectRef]

  implicit val mapReader: ConfigReader[ProjectMapping] =
    genericMapReader(str =>
      ProjectRef.parse(str).leftMap(e => CannotConvert(str, classOf[ProjectRef].getSimpleName, e))
    )

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

  def load(externalConfigStream: Stream[IO, Byte]): IO[ShipConfig] = {
    merge(externalConfigStream).map(_._1)
  }

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
