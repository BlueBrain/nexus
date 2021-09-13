package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}
import com.typesafe.config.Config
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.util.Try

/**
  * Configuration for the ElasticSearchView plugin.
  *
  * @param base
  *   the base uri to the Elasticsearch HTTP endpoint
  * @param client
  *   configuration of the Elasticsearch client
  * @param aggregate
  *   configuration of the underlying aggregate
  * @param keyValueStore
  *   configuration of the underlying key/value store
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  * @param cacheIndexing
  *   configuration of the cache indexing process
  * @param indexing
  *   configuration of the external indexing process
  * @param maxViewRefs
  *   configuration of the maximum number of view references allowed on an aggregated view
  * @param idleTimeout
  *   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  * @param syncIndexingRefresh
  *   the value for `refresh` Elasticsearch parameter for synchronous indexing
  * @param maxIndexPathLength
  *   the maximum length of the URL path for elasticsearch queries
  */
final case class ElasticSearchViewsConfig(
    base: Uri,
    client: HttpClientConfig,
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    indexing: ExternalIndexingConfig,
    maxViewRefs: Int,
    idleTimeout: Duration,
    syncIndexingRefresh: Refresh,
    maxIndexPathLength: Int
)

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
  implicit private val uriConfigReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str))
      .filter(_.isAbsolute)
      .toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

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
