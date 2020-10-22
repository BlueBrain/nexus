package ch.epfl.bluebrain.nexus.delta.service

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig

/**
  * Configuration for indexing process.
  *
  * @param concurrency    indexing concurrency
  * @param retryStrategy  indexing retry strategy configuration
  */
final case class IndexingConfig(concurrency: Int, retryStrategy: RetryStrategyConfig)
