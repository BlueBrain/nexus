package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.service.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

/**
  * Configuration for the Organizations module.
  *
  * @param aggregate     configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  * @param pagination    configuration for how pagination should behave in listing operations
  * @param indexing      configuration of the indexing process
  */
final case class ProjectsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    indexing: IndexingConfig
)
