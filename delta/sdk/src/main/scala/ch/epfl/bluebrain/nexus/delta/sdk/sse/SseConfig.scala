package ch.epfl.bluebrain.nexus.delta.sdk.sse

import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class SseConfig(query: QueryConfig, cache: CacheConfig)

object SseConfig {

  implicit final val sseConfigReader: ConfigReader[SseConfig] = deriveReader[SseConfig]
}
