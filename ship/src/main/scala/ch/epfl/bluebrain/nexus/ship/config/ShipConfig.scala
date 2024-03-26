package ch.epfl.bluebrain.nexus.ship.config

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.config.Configs
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, EventLogConfig}
import com.typesafe.config.Config
import fs2.io.file.Path
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class ShipConfig(
    baseUri: BaseUri,
    database: DatabaseConfig,
    eventLog: EventLogConfig,
    organizations: OrganizationCreationConfig,
    serviceAccount: ServiceAccountConfig
)

object ShipConfig {

  implicit final val shipConfigReader: ConfigReader[ShipConfig] =
    deriveReader[ShipConfig]

  def merge(externalConfigPath: Option[Path]): IO[(ShipConfig, Config)] =
    for {
      externalConfig <- Configs.parseFile(externalConfigPath.map(_.toNioPath.toFile))
      defaultConfig  <- Configs.parseResource("/default.conf")
      result         <- Configs.merge[ShipConfig]("ship", externalConfig, defaultConfig)
    } yield result

  def load(externalConfigPath: Option[Path]): IO[ShipConfig] =
    merge(externalConfigPath).map(_._1)
}
