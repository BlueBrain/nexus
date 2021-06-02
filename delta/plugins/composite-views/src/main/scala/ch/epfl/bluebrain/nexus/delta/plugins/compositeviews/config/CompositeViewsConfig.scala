package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config

import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.{RemoteSourceClientConfig, SourcesConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import scala.concurrent.duration.FiniteDuration

/**
  * The composite view configuration.
  *
  * @param sources               the configuration of the composite views sources
  * @param maxProjections        maximum number of projections allowed
  * @param aggregate             aggregate config
  * @param keyValueStore         key value store config
  * @param pagination            pagination config
  * @param cacheIndexing         the cache indexing config
  * @param elasticSearchIndexing the Elasticsearch indexing config
  * @param blazegraphIndexing    the Blazegraph indexing config
  * @param remoteSourceClient    the HTTP client configuration for a remote source
  * @param minIntervalRebuild    the minimum allowed value for periodic rebuild strategy
  * @param idleTimeout   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  */
final case class CompositeViewsConfig(
    sources: SourcesConfig,
    maxProjections: Int,
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    elasticSearchIndexing: ExternalIndexingConfig,
    blazegraphIndexing: ExternalIndexingConfig,
    remoteSourceClient: RemoteSourceClientConfig,
    minIntervalRebuild: FiniteDuration,
    idleTimeout: FiniteDuration
)

object CompositeViewsConfig {

  /**
    * The sources configuration
    *
    * @param maxBatchSize  the maximum batching size, corresponding to the maximum number of Elasticsearch documents uploaded on a bulk request.
    *                      In this window, duplicated persistence ids are discarded
    * @param maxTimeWindow the maximum batching duration. In this window, duplicated persistence ids are discarded
    * @param maxSources    maximum number of sources allowed
    * @param retry         configuration for source retry strategy
    */
  final case class SourcesConfig(
      maxBatchSize: Int,
      maxTimeWindow: FiniteDuration,
      maxSources: Int,
      retry: RetryStrategyConfig
  )

  /**
    * Remote source client configuration
    * @param http           http client configuration
    * @param retryDelay     SSE client retry delay
    * @param maxBatchSize   the maximum batching size, corresponding to the maximum number of documents uploaded on a bulk request.
    *                         In this window, duplicated persistence ids are discarded
    * @param maxTimeWindow  the maximum batching duration. In this window, duplicated persistence ids are discarded
    */
  final case class RemoteSourceClientConfig(
      http: HttpClientConfig,
      retryDelay: FiniteDuration,
      maxBatchSize: Int,
      maxTimeWindow: FiniteDuration
  )

  /**
    * Converts a [[Config]] into an [[CompositeViewsConfig]]
    */
  def load(config: Config): UIO[CompositeViewsConfig] =
    UIO.delay {
      ConfigSource
        .fromConfig(config)
        .at("plugins.composite-views")
        .loadOrThrow[CompositeViewsConfig]
    }
}
