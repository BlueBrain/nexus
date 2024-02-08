package ch.epfl.bluebrain.nexus.delta.plugins.storage

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FilesConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import com.typesafe.config.Config
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

final case class StoragePluginConfig(
    enableDefaultCreation: Boolean,
    storages: StoragesConfig,
    files: FilesConfig,
    defaults: Defaults
)

object StoragePluginConfig {

  private val logger = Logger[StoragePluginConfig]

  /**
    * Converts a [[Config]] into an [[StoragePluginConfig]]
    */
  def load(config: Config): IO[StoragePluginConfig] =
    IO
      .delay {
        ConfigSource
          .fromConfig(config)
          .at("plugins.storage")
          .loadOrThrow[StoragePluginConfig]
      }
      .flatTap { config =>
        IO.whenA(config.storages.storageTypeConfig.amazon.isDefined) {
          logger.info("Amazon S3 storage is enabled")
        } >>
          IO.whenA(config.storages.storageTypeConfig.remoteDisk.isDefined) {
            logger.info("Remote-disk storage is enabled")
          }
      }

  implicit final val storagePluginConfig: ConfigReader[StoragePluginConfig] =
    deriveReader[StoragePluginConfig]
}
