package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig.Credentials
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.SinkConfig.SinkConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.{BlazegraphAccess, RemoteSourceClientConfig, SourcesConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, EventLogConfig}
import com.typesafe.config.Config
import monix.bio.UIO
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * The composite view configuration.
  *
  * @param sources
  *   the configuration of the composite views sources
  * @param blazegraphAccess
  *   the configuration of the Blazegraph instance used for composite views
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
  * @param indexingEnabled
  *   if false, disables composite view indexing
  * @param sinkConfig
  *   type of sink used for composite indexing
  */
final case class CompositeViewsConfig(
    sources: SourcesConfig,
    blazegraphAccess: BlazegraphAccess,
    prefix: String,
    maxProjections: Int,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    remoteSourceClient: RemoteSourceClientConfig,
    minIntervalRebuild: FiniteDuration,
    blazegraphBatch: BatchConfig,
    elasticsearchBatch: BatchConfig,
    restartCheckInterval: FiniteDuration,
    indexingEnabled: Boolean,
    sinkConfig: SinkConfig
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
    * The configuration of the blazegraph instance used for composite views. By default it uses values from the
    * blazegraph plugin.
    *
    * @param base
    *   the base uri to the Blazegraph HTTP endpoint
    * @param credentials
    *   the Blazegraph HTTP endpoint credentials
    * @param queryTimeout
    *   the Blazegraph query timeout
    */
  final case class BlazegraphAccess(
      base: Uri,
      credentials: Option[Credentials],
      queryTimeout: Duration
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

  object SinkConfig {

    /** Represents the choice of composite sink */
    sealed trait SinkConfig

    /** A sink that only supports querying one resource at once from blazegraph */
    case object Legacy extends SinkConfig

    /** A sink that supports querying multiple resources at once from blazegraph */
    case object Batch extends SinkConfig

    implicit val sinkConfigReaderString: ConfigReader[SinkConfig] =
      ConfigReader.fromString {
        case "batch"  => Right(Batch)
        case "legacy" => Right(Legacy)
        case _        => Right(Legacy)
      }
  }

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
    deriveReader[CompositeViewsConfig]
}
