package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FilesConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import monix.bio.UIO
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

final case class StoragePluginConfig(storages: StoragesConfig, files: FilesConfig)

object StoragePluginConfig {

  private val logger: Logger = Logger[StoragePluginConfig]

  /**
    * Converts a [[Config]] into an [[StoragePluginConfig]]
    */
  def load(config: Config): UIO[StoragePluginConfig] =
    UIO
      .delay {
        ConfigSource
          .fromConfig(config)
          .at("storage")
          .loadOrThrow[StoragePluginConfig]
      }
      .tapEval { config =>
        UIO.when(config.storages.storageTypeConfig.amazon.isDefined) {
          UIO.delay(logger.info("Amazon S3 storage is enabled"))
        } >>
          UIO.when(config.storages.storageTypeConfig.remoteDisk.isDefined) {
            UIO.delay(logger.info("Remote-disk storage is enabled"))
          }
      }

  implicit final val storagePluginConfig: ConfigReader[StoragePluginConfig] =
    deriveReader[StoragePluginConfig]
}
