package ch.epfl.bluebrain.nexus.delta.kernel

import com.typesafe.scalalogging.Logger
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * Configuration for indexing process.
  *
  * @param concurrency    indexing concurrency
  * @param retry          indexing retry strategy configuration
  */
final case class IndexingConfig(concurrency: Int, retry: RetryStrategyConfig)

object IndexingConfig {
  implicit final val indexingConfigReader: ConfigReader[IndexingConfig] = {
    val logger: Logger = Logger[IndexingConfig]

    @nowarn("cat=unused")
    implicit val retryStrategyConfig: ConfigReader[RetryStrategy] = RetryStrategy.configReader(logger, "indexing")
    deriveReader[IndexingConfig]
  }
}
