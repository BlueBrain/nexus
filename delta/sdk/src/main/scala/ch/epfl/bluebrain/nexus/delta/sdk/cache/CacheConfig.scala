package ch.epfl.bluebrain.nexus.delta.sdk.cache

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * The cache configuration.
  *
  * @param maxSize
  *   max size for the cache
  * @param expireAfter
  *   duration after expiration of the cache should occur
  */
final case class CacheConfig(
    maxSize: Int,
    expireAfter: FiniteDuration
)

object CacheConfig {

  implicit final val cacheConfigReader: ConfigReader[CacheConfig] =
    deriveReader[CacheConfig]

}
