package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.{RemoteSourceClientConfig, SourcesConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, EventLogConfig}
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.FiniteDuration

/**
  * The composite view configuration.
  *
  * @param sources
  *   the configuration of the composite views sources
  * @param prefix
  *   prefix for indices and namespaces
  * @param maxProjections
  *   maximum number of projections allowed
  * @param eventLog
  *   configuration of the event log
  * @param pagination
  *   pagination config
  * @param remoteSourceClient
  *   the HTTP client configuration for a remote source
  * @param minIntervalRebuild
  *   the minimum allowed value for periodic rebuild strategy
  * @param blazegraphBatch
  *   the batch configuration for indexing into the blazegraph common space and the blazegraph projections
  * @param elasticsearchBatch
  *   the batch configuration for indexing into the elasticsearch projections
  * @param restartCheckInterval
  *   the interval at which a view will look for requested restarts
  */
final case class CompositeViewsConfig(
    sources: SourcesConfig,
    prefix: String,
    maxProjections: Int,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    remoteSourceClient: RemoteSourceClientConfig,
    minIntervalRebuild: FiniteDuration,
    blazegraphBatch: BatchConfig,
    elasticsearchBatch: BatchConfig,
    restartCheckInterval: FiniteDuration
)

object CompositeViewsConfig {

  /**
    * The sources configuration
    *
    * @param maxSources
    *   maximum number of sources allowed
    */
  final case class SourcesConfig(
      maxSources: Int
  )

  /**
    * Remote source client configuration
    * @param http
    *   http client configuration
    * @param retryDelay
    *   SSE client retry delay
    * @param maxBatchSize
    *   the maximum batching size, corresponding to the maximum number of documents uploaded on a bulk request. In this
    *   window, duplicated persistence ids are discarded
    * @param maxTimeWindow
    *   the maximum batching duration. In this window, duplicated persistence ids are discarded
    */
  final case class RemoteSourceClientConfig(
      http: HttpClientConfig,
      retryDelay: FiniteDuration,
      maxBatchSize: Int,
      maxTimeWindow: FiniteDuration
  )

  /**
    * Converts a [[Config]] into an [[CompositeViewsConfig]]
    */
  def load(config: Config): CompositeViewsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.composite-views")
      .loadOrThrow[CompositeViewsConfig]

  implicit final val compositeViewsConfigReader: ConfigReader[CompositeViewsConfig] =
    deriveReader[CompositeViewsConfig]
}
