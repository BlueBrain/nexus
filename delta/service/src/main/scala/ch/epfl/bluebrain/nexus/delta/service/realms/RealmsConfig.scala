package ch.epfl.bluebrain.nexus.delta.service.realms

import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

final case class RealmsConfig(aggregate: AggregateConfig, keyValueStore: KeyValueStoreConfig)
