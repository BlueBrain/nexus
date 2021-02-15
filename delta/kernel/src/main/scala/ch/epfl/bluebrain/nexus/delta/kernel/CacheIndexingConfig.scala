package ch.epfl.bluebrain.nexus.delta.kernel

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for indexing into a Distributed Data cache.
  *
  * @param concurrency    indexing concurrency
  * @param retry          indexing retry strategy configuration
  */
final case class CacheIndexingConfig(concurrency: Int, retry: RetryStrategyConfig)

object CacheIndexingConfig {
  implicit final val cacheIndexingConfigReader: ConfigReader[CacheIndexingConfig] =
    deriveReader[CacheIndexingConfig]
}
