package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import akka.http.scaladsl.model.Uri
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import pureconfig.generic.auto._
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for migration to 1.8 checks
  * @param previousPrefix
  *   the previous prefix for indexing
  * @param blazegraphBase
  *   the endpoint of the blazegraph instance used by Delta 1.7
  * @param deltaBase
  *   the endpoint of a running Delta 1.7
  * @param newDeltaBase
  *   the endpoint of a running Delta 1.8
  * @param saveInterval
  *   save interval for long running checks
  */
final case class MigrationCheckConfig(
    previousPrefix: String,
    blazegraphBase: Uri,
    deltaBase: Uri,
    newDeltaBase: Uri,
    saveInterval: FiniteDuration,
    maxConcurrent: Int
)

object MigrationCheckConfig {

  private val hoconConfig =
    """
      |migration.check {
      |  # Previous prefix used for indexing
      |  previous-prefix = "delta"
      |  # The endpoint of the blazegraph instance used for Delta 1.7
      |  blazegraph-base = "http://blazegraph:9999/blazegraph"
      |  # The endpoint of the Delta 1.7 instance
      |  delta-base = "http://delta:8080/v1"
      |  # The endpoint of the Delta 1.7 instance
      |  new-delta-base = "http://delta-new:8080/v1"
      |  # Save interval for long running checks
      |  save-interval = 20 seconds
      |  # Max concurrent checks for resources
      |  max-concurrent = 2
      |}
      |""".stripMargin

  private val parseOptions   = ConfigParseOptions.defaults().setAllowMissing(false)
  private val resolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)

  def load(): MigrationCheckConfig = {
    implicit val migrationCheckConfigReader: ConfigReader[MigrationCheckConfig] =
      deriveReader[MigrationCheckConfig]
    ConfigSource
      .fromConfig(
        ConfigFactory
          .defaultOverrides()
          .withFallback(ConfigFactory.parseString(hoconConfig, parseOptions))
          .resolve(resolveOptions)
      )
      .at("migration.check")
      .loadOrThrow[MigrationCheckConfig]
  }
}
