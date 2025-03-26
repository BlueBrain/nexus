package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlTarget
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, EventLogConfig}
import com.typesafe.config.Config
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration._

/**
  * Configuration for the Blazegraph views module.
  *
  * @param base
  *   the base uri to the Blazegraph HTTP endpoint
  * @param credentials
  *   the Blazegraph HTTP endpoint credentials
  * @param indexingClient
  *   configuration of the indexing Blazegraph client
  * @param queryTimeout
  *   the Blazegraph query timeout
  * @param slowQueries
  *   configuration of slow queries
  * @param eventLog
  *   configuration of the event log
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  * @param prefix
  *   prefix for namespaces
  * @param maxViewRefs
  *   configuration of the maximum number of view references allowed on an aggregated view
  * @param syncIndexingTimeout
  *   the maximum duration for synchronous indexing to complete
  * @param defaults
  *   default values for the default Blazegraph views
  * @param indexingEnabled
  *   if false, disables Blazegraph indexing
  */
final case class BlazegraphViewsConfig(
    base: Uri,
    sparqlTarget: SparqlTarget,
    credentials: Option[BasicHttpCredentials],
    indexingClient: HttpClientConfig,
    queryTimeout: Duration,
    slowQueries: SlowQueriesConfig,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    batch: BatchConfig,
    prefix: String,
    maxViewRefs: Int,
    syncIndexingTimeout: FiniteDuration,
    defaults: Defaults,
    indexingEnabled: Boolean
)

object BlazegraphViewsConfig {

  /**
    * Converts a [[Config]] into an [[BlazegraphViewsConfig]]
    */
  def load(config: Config): BlazegraphViewsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.blazegraph")
      .loadOrThrow[BlazegraphViewsConfig]

  implicit final val blazegraphViewsConfigConfigReader: ConfigReader[BlazegraphViewsConfig] =
    deriveReader[BlazegraphViewsConfig]
}
