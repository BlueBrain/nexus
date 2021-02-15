package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.sourcing.config.{AggregateConfig, PersistProgressConfig}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Projects module.
  *
  * @param aggregate             configuration of the underlying aggregate
  * @param keyValueStore         configuration of the underlying key/value store
  * @param pagination            configuration for how pagination should behave in listing operations
  * @param cacheIndexing         configuration of the cache indexing process
  * @param persistProgressConfig configuration for the persistence of progress of projections
  */
final case class ProjectsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    persistProgressConfig: PersistProgressConfig
)

object ProjectsConfig {
  implicit final val projectConfigReader: ConfigReader[ProjectsConfig] =
    deriveReader[ProjectsConfig]
}
