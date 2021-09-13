package ch.epfl.bluebrain.nexus.delta.plugins.statistics.config

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
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
  * @param keyValueStore
  *   configuration of the underlying key/value store
  * @param indexing
  *   configuration of the external indexing process
  * @param idleTimeout
  *   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  */
final case class StatisticsConfig(
    base: Uri,
    keyValueStore: KeyValueStoreConfig,
    indexing: ExternalIndexingConfig,
    idleTimeout: Duration
)

object StatisticsConfig {

  /**
    * Converts a [[Config]] into an [[StatisticsConfig]]
    */
  def load(config: Config): StatisticsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.statistics")
      .loadOrThrow[StatisticsConfig]

  @nowarn("cat=unused")
  implicit private val uriConfigReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str))
      .filter(_.isAbsolute)
      .toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  implicit final val statisticsConfigReader: ConfigReader[StatisticsConfig] =
    deriveReader[StatisticsConfig].emap { c =>
      Either.cond(
        c.idleTimeout.gteq(10.minutes),
        c,
        new FailureReason { override def description: String = "'idle-timeout' must be greater than 10 minutes" }
      )
    }
}
