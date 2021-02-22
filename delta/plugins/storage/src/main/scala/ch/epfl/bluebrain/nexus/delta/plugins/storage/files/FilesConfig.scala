package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the files module.
  *
  * @param aggregate         configuration of the underlying aggregate
  * @param cacheIndexing     configuration of the cache indexing process
  */
final case class FilesConfig(aggregate: AggregateConfig, cacheIndexing: CacheIndexingConfig)

object FilesConfig {
  implicit final val filesConfigReader: ConfigReader[FilesConfig] =
    deriveReader[FilesConfig]
}
