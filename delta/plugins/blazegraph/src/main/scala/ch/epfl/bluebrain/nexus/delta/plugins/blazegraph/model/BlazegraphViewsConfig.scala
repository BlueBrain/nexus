package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig.Credentials
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}
import com.typesafe.config.Config
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.util.Try

/**
  * Configuration for the Blazegraph views module.
  *
  * @param base          the base uri to the Blazegraph HTTP endpoint
  * @param credentials   the Blazegraph HTTP endpoint credentials
  * @param client        configuration of the Blazegraph client
  * @param queryTimeout  the Blazegraph query timeout
  * @param aggregate     configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  * @param pagination    configuration for how pagination should behave in listing operations
  * @param cacheIndexing configuration of the cache indexing process
  * @param indexing      configuration of the external indexing process
  * @param maxViewRefs   configuration of the maximum number of view references allowed on an aggregated view
  * @param idleTimeout   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  */
final case class BlazegraphViewsConfig(
    base: Uri,
    credentials: Option[Credentials],
    client: HttpClientConfig,
    queryTimeout: Duration,
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    indexing: ExternalIndexingConfig,
    maxViewRefs: Int,
    idleTimeout: Duration
)

object BlazegraphViewsConfig {

  /**
    * The Blazegraph HTTP endpoint credentials
    *
    * @param username the credentials username
    * @param password the credentials password
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

  @nowarn("cat=unused")
  implicit private val uriConfigReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str))
      .filter(_.isAbsolute)
      .toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  implicit final val blazegraphViewsConfigConfigReader: ConfigReader[BlazegraphViewsConfig] =
    deriveReader[BlazegraphViewsConfig].emap { c =>
      Either.cond(
        c.idleTimeout.gteq(10.minutes),
        c,
        new FailureReason { override def description: String = "'idle-timeout' must be greater than 10 minutes" }
      )
    }
}
