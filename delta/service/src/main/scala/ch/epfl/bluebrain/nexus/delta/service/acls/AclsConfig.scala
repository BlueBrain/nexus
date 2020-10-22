package ch.epfl.bluebrain.nexus.delta.service.acls

import ch.epfl.bluebrain.nexus.delta.service.acls.AclsConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategy
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

/** Configuration for the ACLs module
  *
  * @param aggregate      configuration of the underlying aggregate
  * @param keyValueStore  configuration of the underlying key/value store
  * @param indexing       configuration of the indexing process
  */
final case class AclsConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    indexing: IndexingConfig
)

object AclsConfig {

  /**
    * Configuration for ACLs indexing process.
    *
    * @param concurrency    indexing concurrency
    * @param retryStrategy  indexing retry strategy
    */
  final case class IndexingConfig(concurrency: Int, retryStrategy: RetryStrategy)

}
