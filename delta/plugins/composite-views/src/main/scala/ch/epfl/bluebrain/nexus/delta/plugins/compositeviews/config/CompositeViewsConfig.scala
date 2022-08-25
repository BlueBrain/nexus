package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.{RemoteSourceClientConfig, SourcesConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.error.FailureReason
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

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
  * @param idleTimeout
  *   the idle duration after which an indexing stream will be stopped
  */
final case class CompositeViewsConfig(
    sources: SourcesConfig,
    prefix: String,
    maxProjections: Int,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    remoteSourceClient: RemoteSourceClientConfig,
    minIntervalRebuild: FiniteDuration,
    idleTimeout: FiniteDuration
)

object CompositeViewsConfig {

  /**
    * The sources configuration
    *
    * @param maxBatchSize
    *   the maximum batching size, corresponding to the maximum number of Elasticsearch documents uploaded on a bulk
    *   request. In this window, duplicated persistence ids are discarded
    * @param maxTimeWindow
    *   the maximum batching duration. In this window, duplicated persistence ids are discarded
    * @param maxSources
    *   maximum number of sources allowed
    * @param retry
    *   configuration for source retry strategy
    */
  final case class SourcesConfig(
      maxBatchSize: Int,
      maxTimeWindow: FiniteDuration,
      maxSources: Int,
      retry: RetryStrategyConfig
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
  def load(config: Config): UIO[CompositeViewsConfig] =
    UIO.delay {
      ConfigSource
        .fromConfig(config)
        .at("plugins.composite-views")
        .loadOrThrow[CompositeViewsConfig]
    }

  implicit final val compositeViewsConfigReader: ConfigReader[CompositeViewsConfig] =
    deriveReader[CompositeViewsConfig].emap { c =>
      Either.cond(
        c.idleTimeout.gteq(10.minutes),
        c,
        new FailureReason { override def description: String = "'idle-timeout' must be greater than 10 minutes" }
      )
    }
}
