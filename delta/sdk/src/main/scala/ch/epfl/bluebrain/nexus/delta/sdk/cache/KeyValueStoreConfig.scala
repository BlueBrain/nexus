package ch.epfl.bluebrain.nexus.delta.sdk.cache

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * KeyValueStore configuration.
  *
  * @param askTimeout
  *   the maximum duration to wait for the replicator to reply
  * @param writeLocal
  *   the value will immediately only be written to the local replica, and later disseminated with gossip
  * @param consistencyTimeout
  *   the maximum duration to wait for a consistent read or write across all nodes of the cluster
  * @param retry
  *   the retry strategy configuration
  */
final case class KeyValueStoreConfig(
    askTimeout: FiniteDuration,
    writeLocal: Boolean,
    consistencyTimeout: FiniteDuration,
    retry: RetryStrategyConfig
)

object KeyValueStoreConfig {
  implicit final val keyValueStoreConfigReader: ConfigReader[KeyValueStoreConfig] =
    deriveReader[KeyValueStoreConfig]
}
