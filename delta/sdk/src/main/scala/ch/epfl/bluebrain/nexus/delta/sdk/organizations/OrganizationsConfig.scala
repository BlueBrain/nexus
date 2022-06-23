package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Organizations module.
  *
  * @param eventLog
  *   The event log configuration
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  * @param cache
  *   the cache configuration for the uuids cache
  */
final case class OrganizationsConfig(
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    cache: CacheConfig
)

object OrganizationsConfig {
  implicit final val orgsConfigReader: ConfigReader[OrganizationsConfig] =
    deriveReader[OrganizationsConfig]
}
