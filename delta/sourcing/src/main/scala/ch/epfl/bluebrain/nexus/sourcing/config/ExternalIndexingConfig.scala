package ch.epfl.bluebrain.nexus.sourcing.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for indexing into a an external indexer (i.e. elasticsearch)
  *
  * @param prefix          the prefix to add to the created indices
  * @param batchMaxSize    the maximum batching size. In this window, duplicated persistence ids are discarded
  * @param batchMaxTimeout the maximum batching duration. In this window, duplicated persistence ids are discarded
  * @param retry           indexing retry strategy configuration
  * @param persist         configuration for the persistence of progress of projections
  */
final case class ExternalIndexingConfig(
    prefix: String,
    batchMaxSize: Int,
    batchMaxTimeout: FiniteDuration,
    retry: RetryStrategyConfig,
    persist: PersistProgressConfig
)

object ExternalIndexingConfig {
  implicit final val externalIndexingConfigReader: ConfigReader[ExternalIndexingConfig] =
    deriveReader[ExternalIndexingConfig]
}
