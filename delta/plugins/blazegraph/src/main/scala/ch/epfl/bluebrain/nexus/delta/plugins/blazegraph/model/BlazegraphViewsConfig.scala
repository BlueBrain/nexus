package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.kernel.{IndexingConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig.BlazegraphClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.sourcing.config.{AggregateConfig, PersistProgressConfig}

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for the Blazegraph views module.
  *
  * @param aggregate      configuration of the underlying aggregate
  * @param keyValueStore  configuration of the underlying key/value store
  * @param pagination     configuration for how pagination should behave in listing operations
  * @param indexing       configuration of the indexing process
  */
final case class BlazegraphViewsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    indexing: IndexingConfig,
    persist: PersistProgressConfig,
    client: BlazegraphClientConfig
)

object BlazegraphViewsConfig {

  final case class BlazegraphClientConfig(
      indexingBulkSize: Int,
      indexingBulkMaxWait: FiniteDuration,
      retry: RetryStrategyConfig,
      indexPrefix: String
  )

}
