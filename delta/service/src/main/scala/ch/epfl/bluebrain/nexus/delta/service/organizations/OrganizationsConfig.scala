package ch.epfl.bluebrain.nexus.delta.service.organizations

import ch.epfl.bluebrain.nexus.delta.kernel.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Organizations module.
  *
  * @param aggregate     configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  * @param pagination    configuration for how pagination should behave in listing operations
  * @param indexing      configuration of the indexing process
  */
final case class OrganizationsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    indexing: IndexingConfig
)

object OrganizationsConfig {
  implicit final val orgsConfigReader: ConfigReader[OrganizationsConfig] =
    deriveReader[OrganizationsConfig]
}
