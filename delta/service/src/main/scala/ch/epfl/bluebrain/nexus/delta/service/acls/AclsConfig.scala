package ch.epfl.bluebrain.nexus.delta.service.acls

import ch.epfl.bluebrain.nexus.delta.kernel.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the ACLs module
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
  implicit final val aclsConfigReader: ConfigReader[AclsConfig] =
    deriveReader[AclsConfig]
}
