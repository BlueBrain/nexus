package ch.epfl.bluebrain.nexus.ship.config

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import fs2.io.file.Path
import pureconfig.error.ConfigReaderException
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

final case class ShipConfig(database: DatabaseConfig)

object ShipConfig {

  private val parseOptions = ConfigParseOptions.defaults().setAllowMissing(false)
  private val resolverOptions = ConfigResolveOptions.defaults()

  implicit final val shipConfigReader: ConfigReader[ShipConfig] =
    deriveReader[ShipConfig]

  def load(externalConfigPath: Option[Path]) =
    for {
      externalConfig <- IO.blocking(externalConfigPath.fold(ConfigFactory.empty()) { path =>
        ConfigFactory.parseFile(path.toNioPath.toFile, parseOptions)
      })
      defaultConfig             <- IO.blocking(ConfigFactory.parseResources("default.conf", parseOptions))
      merged = (externalConfig, defaultConfig).foldLeft(ConfigFactory.defaultOverrides())(_ withFallback _).withFallback(ConfigFactory.load())
        .resolve(resolverOptions)
      config <- IO.fromEither(ConfigSource.fromConfig(merged).at("ship").load[ShipConfig].leftMap(ConfigReaderException(_)))
    } yield config
}
