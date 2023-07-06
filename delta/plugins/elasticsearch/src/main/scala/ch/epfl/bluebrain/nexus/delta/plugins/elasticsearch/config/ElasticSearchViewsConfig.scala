package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, EventLogConfig, QueryConfig}
import com.typesafe.config.Config
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.annotation.nowarn
import scala.concurrent.duration._

/**
  * Configuration for the ElasticSearchView plugin.
  *
  * @param base
  *   the base uri to the Elasticsearch HTTP endpoint
  * @param credentials
  *   the credentials to authenticate to the Elasticsearch endpoint
  * @param client
  *   configuration of the Elasticsearch client
  * @param eventLog
  *   configuration of the event log
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  * @param batch
  *   a configuration definition how often we want to push to Elasticsearch
  * @param prefix
  *   prefix for indices
  * @param maxViewRefs
  *   configuration of the maximum number of view references allowed on an aggregated view
  * @param idleTimeout
  *   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  * @param syncIndexingTimeout
  *   the maximum duration for synchronous indexing to complete
  * @param syncIndexingRefresh
  *   the value for `refresh` Elasticsearch parameter for synchronous indexing
  * @param maxIndexPathLength
  *   the maximum length of the URL path for elasticsearch queries
  * @param defaults
  *   default values for the default Elasticsearch views
  * @param metricsQuery
  *   query configuration for the metrics projection
  * @param disableIndexing
  *   if true, disables the Elasticsearch indexing
  */
final case class ElasticSearchViewsConfig(
    base: Uri,
    credentials: Option[BasicHttpCredentials],
    client: HttpClientConfig,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    batch: BatchConfig,
    prefix: String,
    maxViewRefs: Int,
    idleTimeout: Duration,
    syncIndexingTimeout: FiniteDuration,
    syncIndexingRefresh: Refresh,
    maxIndexPathLength: Int,
    defaults: Defaults,
    metricsQuery: QueryConfig,
    disableIndexing: Boolean
) {
  def indexingEnabled: Boolean = !disableIndexing
}

object ElasticSearchViewsConfig {

  /**
    * Converts a [[Config]] into an [[ElasticSearchViewsConfig]]
    */
  def load(config: Config): ElasticSearchViewsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.elasticsearch")
      .loadOrThrow[ElasticSearchViewsConfig]

  @nowarn("cat=unused")
  implicit private val refreshConfigReader: ConfigReader[Refresh] = ConfigReader.fromString {
    case "true"     => Right(Refresh.True)
    case "false"    => Right(Refresh.False)
    case "wait_for" => Right(Refresh.WaitFor)
    case other      =>
      Left(
        CannotConvert(
          other,
          classOf[Refresh].getSimpleName,
          s"Incorrect value for 'refresh' parameter, allowed values are 'true', 'false', 'wait_for', got '$other' instead."
        )
      )
  }

  implicit final val elasticSearchViewsConfigReader: ConfigReader[ElasticSearchViewsConfig] =
    deriveReader[ElasticSearchViewsConfig].emap { c =>
      Either.cond(
        c.idleTimeout.gteq(10.minutes),
        c,
        new FailureReason { override def description: String = "'idle-timeout' must be greater than 10 minutes" }
      )
    }
}
