package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.kernel.IndexingConfig
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the files module.
  *
  * @param aggregate         configuration of the underlying aggregate
  * @param indexing          configuration of the indexing process
  */
final case class FilesConfig(aggregate: AggregateConfig, indexing: IndexingConfig)

object FilesConfig {
  implicit final val filesConfigReader: ConfigReader[FilesConfig] =
    deriveReader[FilesConfig]
}
