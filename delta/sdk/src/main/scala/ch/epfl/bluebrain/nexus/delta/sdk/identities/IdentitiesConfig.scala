package ch.epfl.bluebrain.nexus.delta.sdk.identities

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * The identities module config.
  *
  * @param cacheMaxSize
  *   max size for the group cache
  * @param cacheExpiration
  *   duration after expiration of the group cache should occur
  */
final case class IdentitiesConfig(
    cacheMaxSize: Int,
    cacheExpiration: FiniteDuration
)

object IdentitiesConfig {

  implicit final val identitiesConfigReader: ConfigReader[IdentitiesConfig] =
    deriveReader[IdentitiesConfig]

}
