package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FilesConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

final case class StoragePluginConfig(storages: StoragesConfig, files: FilesConfig)

object StoragePluginConfig {

  /**
    * Converts a [[Config]] into an [[StoragePluginConfig]]
    */
  def load(config: Config): UIO[StoragePluginConfig] =
    UIO.delay {
      ConfigSource
        .fromConfig(config)
        .at("storage")
        .loadOrThrow[StoragePluginConfig]
    }

  implicit final val storagePluginConfig: ConfigReader[StoragePluginConfig] =
    deriveReader[StoragePluginConfig]
}
