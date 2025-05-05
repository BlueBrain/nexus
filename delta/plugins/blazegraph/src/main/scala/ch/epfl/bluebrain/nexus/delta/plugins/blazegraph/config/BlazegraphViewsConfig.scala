package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.instances.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlTarget
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import com.typesafe.config.Config
import org.http4s.{BasicCredentials, Uri}
import pureconfig.generic.auto.*
import pureconfig.module.http4s.*
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.*

/**
  * Configuration for the Blazegraph views module.
  *
  * @param base
  *   the base uri to the Blazegraph HTTP endpoint
  * @param credentials
  *   the Blazegraph HTTP endpoint credentials
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
    credentials: Option[BasicCredentials],
    queryTimeout: Duration,
    slowQueries: SlowQueriesConfig,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    batch: BatchConfig,
    retryStrategy: RetryStrategyConfig,
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
