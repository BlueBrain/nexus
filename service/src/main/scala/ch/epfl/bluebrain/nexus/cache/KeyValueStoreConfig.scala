package ch.epfl.bluebrain.nexus.cache

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

import scala.concurrent.duration.FiniteDuration

/**
  * KeyValueStore configuration.
  *
  * @param askTimeout         the maximum duration to wait for the replicator to reply
  * @param consistencyTimeout the maximum duration to wait for a consistent read or write across the cluster
  * @param retry              the retry strategy configuration
  */
final case class KeyValueStoreConfig(
    askTimeout: FiniteDuration,
    consistencyTimeout: FiniteDuration,
    retry: RetryStrategyConfig
)

object KeyValueStoreConfig {
  implicit final val keyValueStoreConfigConvert: ConfigConvert[KeyValueStoreConfig] =
    deriveConvert[KeyValueStoreConfig]

}
