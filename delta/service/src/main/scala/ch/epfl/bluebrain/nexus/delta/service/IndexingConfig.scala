package ch.epfl.bluebrain.nexus.delta.service

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategy

/**
  * Configuration for indexing process.
  *
 * @param concurrency    indexing concurrency
  * @param retryStrategy  indexing retry strategy
  */
final case class IndexingConfig(concurrency: Int, retryStrategy: RetryStrategy)
