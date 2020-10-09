package ch.epfl.bluebrain.nexus.delta.service.realms

import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

/**
  * Configuration for the Realms module
  * @param aggregate configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  */
final case class RealmsConfig(aggregate: AggregateConfig, keyValueStore: KeyValueStoreConfig)
