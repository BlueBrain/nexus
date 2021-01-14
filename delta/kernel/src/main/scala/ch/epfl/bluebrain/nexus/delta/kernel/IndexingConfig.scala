package ch.epfl.bluebrain.nexus.delta.kernel

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for indexing process.
  *
  * @param concurrency    indexing concurrency
  * @param retry          indexing retry strategy configuration
  */
final case class IndexingConfig(concurrency: Int, retry: RetryStrategyConfig)

object IndexingConfig {
  implicit final val indexingConfigReader: ConfigReader[IndexingConfig] =
    deriveReader[IndexingConfig]
}
