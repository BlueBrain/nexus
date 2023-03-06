package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto._

/**
  * Configuration for the Projects module.
  *
  * @param eventLog
  *   configuration of the event log
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  * @param cache
  *   the cache configuration for the uuids cache
  */
final case class ProjectsConfig(
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    cache: CacheConfig
)

object ProjectsConfig {

  implicit final val projectConfigReader: ConfigReader[ProjectsConfig] =
    deriveReader[ProjectsConfig]
}
