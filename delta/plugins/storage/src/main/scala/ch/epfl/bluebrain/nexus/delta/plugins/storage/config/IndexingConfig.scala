package ch.epfl.bluebrain.nexus.delta.plugins.storage.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig

/**
  * Configuration for indexing process.
  *
  * @param concurrency    indexing concurrency
  * @param retry          indexing retry strategy configuration
  */
//TODO: ported from service module, we might want to avoid this duplication
final case class IndexingConfig(concurrency: Int, retry: RetryStrategyConfig)
