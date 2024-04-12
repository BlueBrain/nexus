package ch.epfl.bluebrain.nexus.ship.config

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.config.Configs
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, EventLogConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig.ProjectMapping
import com.typesafe.config.Config
import fs2.io.file.Path
import pureconfig.ConfigReader
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import java.io.File

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

  def merge(externalConfigPath: Option[Path]): IO[(ShipConfig, Config)] =
    mergeFromFile(externalConfigPath.map(_.toNioPath.toFile))

  def mergeFromFile(externalConfigFile: Option[File]): IO[(ShipConfig, Config)] =
    for {
      externalConfig <- Configs.parseFile(externalConfigFile)
      defaultConfig  <- Configs.parseResource("ship-default.conf")
      result         <- Configs.merge[ShipConfig]("ship", externalConfig, defaultConfig)
    } yield result

  def load(externalConfigPath: Option[Path]): IO[ShipConfig] =
    merge(externalConfigPath).map(_._1)

  def loadFromFile(externalConfigFile: Option[File]): IO[ShipConfig] =
    mergeFromFile(externalConfigFile).map(_._1)
}
