package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.ConfigSource
import pureconfig.generic.auto._

/**
  * The composite view configuration.
  *
  * @param maxSources     maximum number of sources allowed
  * @param maxProjections maximum number of projections allowed
  * @param aggregate      aggregate config
  * @param keyValueStore  key value store config
  * @param pagination     pagination config
  * @param indexing       indexing config.
  */
final case class CompositeViewsConfig(
    maxSources: Int,
    maxProjections: Int,
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    indexing: ExternalIndexingConfig
)

object CompositeViewsConfig {

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
