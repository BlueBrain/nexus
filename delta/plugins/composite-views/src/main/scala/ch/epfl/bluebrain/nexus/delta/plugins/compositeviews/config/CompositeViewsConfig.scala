package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config

import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SourcesConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
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
  */
final case class CompositeViewsConfig(
    sources: SourcesConfig,
    maxProjections: Int,
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    elasticSearchIndexing: ExternalIndexingConfig,
    blazegraphIndexing: ExternalIndexingConfig
)

object CompositeViewsConfig {

  /**
    * The sources configuration
    *
    * @param maxBatchSize  the maximum batching size, corresponding to the maximum number of Elasticsearch documents uploaded on a bulk request.
    *                      In this window, duplicated persistence ids are discarded
    * @param maxTimeWindow the maximum batching duration. In this window, duplicated persistence ids are discarded
    * @param maxSources    maximum number of sources allowed
    */
  final case class SourcesConfig(maxBatchSize: Int, maxTimeWindow: FiniteDuration, maxSources: Int)

  /**
    * Converts a [[Config]] into an [[CompositeViewsConfig]]
    */
  def load(config: Config): UIO[CompositeViewsConfig] =
    UIO.delay {
      ConfigSource
        .fromConfig(config)
        .at("composite-views")
        .loadOrThrow[CompositeViewsConfig]
    }
}
