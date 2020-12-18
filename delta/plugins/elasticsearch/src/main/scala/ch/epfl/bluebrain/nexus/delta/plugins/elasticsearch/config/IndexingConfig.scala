package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig

/**
  * Configuration for indexing process.
  *
  * @param concurrency indexing concurrency
  * @param retry       indexing retry strategy configuration
  */
final case class IndexingConfig(concurrency: Int, retry: RetryStrategyConfig)
