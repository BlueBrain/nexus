package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}

/**
  * Configuration for the Blazegraph views module.
  *
  * @param aggregate        configuration of the underlying aggregate
  * @param keyValueStore    configuration of the underlying key/value store
  * @param pagination       configuration for how pagination should behave in listing operations
  * @param cacheIndexing    configuration of the caching indexing process
  * @param indexing         configuration of the external indexing process
  * @param progressCache    configuration of the cache for view projection progress
  * @param incomingOutgoing pagination configuration for incoming/outgoing links
  */
final case class BlazegraphViewsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    indexing: ExternalIndexingConfig,
    progressCache: KeyValueStoreConfig,
    incomingOutgoing: PaginationConfig
)
