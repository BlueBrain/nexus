package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Resolvers module.
  *
  * @param eventLog
  *   configuration of the event log
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  */
final case class ResolversConfig(
    eventLog: EventLogConfig,
    pagination: PaginationConfig
)

object ResolversConfig {
  implicit final val resolversConfigReader: ConfigReader[ResolversConfig] =
    deriveReader[ResolversConfig]
}
