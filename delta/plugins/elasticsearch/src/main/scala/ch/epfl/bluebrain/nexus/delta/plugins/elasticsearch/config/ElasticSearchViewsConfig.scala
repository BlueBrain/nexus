package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}

/**
  * Configuration for the ElasticSearchView plugin.
  *
  * @param aggregate     configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  * @param pagination    configuration for how pagination should behave in listing operations
  * @param cacheIndexing configuration of the cache indexing process
  * @param indexing      configuration of the external indexing process
  * @param progressCache configuration of the cache for view projection progress
  */
final case class ElasticSearchViewsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    indexing: ExternalIndexingConfig,
    progressCache: KeyValueStoreConfig
)
