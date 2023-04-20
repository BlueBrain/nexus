package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration._

/**
  * Configuration for the Blazegraph views module.
  *
  * @param slowQueryThreshold
  *   how long a query takes before it is considered slow
  * @param logTtl
  *   how long to keep logged slow queries
  * @param deleteExpiredLogsEvery
  *   how often to delete expired logs
  */
final case class SlowQueriesConfig(
    slowQueryThreshold: Duration,
    logTtl: FiniteDuration,
    deleteExpiredLogsEvery: FiniteDuration
)

object SlowQueriesConfig {
  implicit final val eventLogConfig: ConfigReader[SlowQueriesConfig] =
    deriveReader[SlowQueriesConfig]
}
