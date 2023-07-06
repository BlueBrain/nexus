package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig.Credentials
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, EventLogConfig}
import com.typesafe.config.Config
import pureconfig.error.FailureReason
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
  * @param queryClient
  *   configuration of the query Blazegraph client
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
  * @param idleTimeout
  *   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  * @param syncIndexingTimeout
  *   the maximum duration for synchronous indexing to complete
  * @param defaults
  *   default values for the default Blazegraph views
  * @param disableIndexing
  *   if true, disables Blazegraph indexing
  */
final case class BlazegraphViewsConfig(
    base: Uri,
    credentials: Option[Credentials],
    indexingClient: HttpClientConfig,
    queryClient: HttpClientConfig,
    queryTimeout: Duration,
    slowQueries: SlowQueriesConfig,
    eventLog: EventLogConfig,
    pagination: PaginationConfig,
    batch: BatchConfig,
    prefix: String,
    maxViewRefs: Int,
    idleTimeout: Duration,
    syncIndexingTimeout: FiniteDuration,
    defaults: Defaults,
    disableIndexing: Boolean
) {
  def indexingEnabled: Boolean = !disableIndexing
}

object BlazegraphViewsConfig {

  /**
    * The Blazegraph HTTP endpoint credentials
    *
    * @param username
    *   the credentials username
    * @param password
    *   the credentials password
    */
  final case class Credentials(username: String, password: Secret[String])

  /**
    * Converts a [[Config]] into an [[BlazegraphViewsConfig]]
    */
  def load(config: Config): BlazegraphViewsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.blazegraph")
      .loadOrThrow[BlazegraphViewsConfig]

  implicit final val blazegraphViewsConfigConfigReader: ConfigReader[BlazegraphViewsConfig] =
    deriveReader[BlazegraphViewsConfig].emap { c =>
      Either.cond(
        c.idleTimeout.gteq(10.minutes),
        c,
        new FailureReason { override def description: String = "'idle-timeout' must be greater than 10 minutes" }
      )
    }
}
