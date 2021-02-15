package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig.BlazegraphClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.sourcing.config.{AggregateConfig, ExternalIndexingConfig, PersistProgressConfig}
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessorConfig

/**
  * Configuration for the Blazegraph views module.
  *
  * @param aggregate     configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  * @param pagination    configuration for how pagination should behave in listing operations
  * @param cacheIndexing configuration of the caching indexing process
  * @param indexing      configuration of the external indexing process
  * @param persist       configuration of the progress projection
  * @param client        configuration of the Blazegraph client
  * @param processor     configuration of the event source processor
  */
final case class BlazegraphViewsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    indexing: ExternalIndexingConfig,
    persist: PersistProgressConfig,
    client: BlazegraphClientConfig,
    processor: EventSourceProcessorConfig
)

object BlazegraphViewsConfig {

  /**
    * Blazegraph client config.
    *
    * @param retry        the retry strategy
    * @param indexPrefix  the namespace prefix
    */
  final case class BlazegraphClientConfig(
      retry: RetryStrategyConfig,
      indexPrefix: String
  )

}
