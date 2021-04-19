package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.sourcing.config.TransientAggregateConfig
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.FiniteDuration

/**
  * Archive plugin configuration.
  *
  * @param priority  the plugin priority
  * @param ttl       the duration after which an archive is removed from the system
  * @param aggregate the aggregate configuration
  */
final case class ArchivePluginConfig(
    priority: Int,
    ttl: FiniteDuration,
    aggregate: TransientAggregateConfig
)
object ArchivePluginConfig {

  /**
    * Converts a [[Config]] into an [[ArchivePluginConfig]]
    */
  def load(config: Config): UIO[ArchivePluginConfig] =
    UIO.delay {
      ConfigSource
        .fromConfig(config)
        .at("plugins.archive")
        .loadOrThrow[ArchivePluginConfig]
    }

  implicit final val archivePluginConfigReader: ConfigReader[ArchivePluginConfig] =
    deriveReader[ArchivePluginConfig]
}
