package ch.epfl.bluebrain.nexus.delta.service.config

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig

/**
  * Configuration for indexing process.
  *
  * @param concurrency    indexing concurrency
  * @param retry          indexing retry strategy configuration
  */
final case class IndexingConfig(concurrency: Int, retry: RetryStrategyConfig)
