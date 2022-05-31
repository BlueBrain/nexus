package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import com.zaxxer.hikari.HikariConfig

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for the sourcing library
  * @param readConfig
  *   the hikari config for database reads but streaming queries
  * @param writeConfig
  *   the hikari config for database writes
  * @param streamingConfig
  *   the hikari config for streaming queries
  * @param tablesAutocreate
  *   allows to create automatically tables at startup
  * @param query
  *   the configuration for streaming queries
  */
final case class SourcingConfig(
    readConfig: HikariConfig,
    writeConfig: HikariConfig,
    streamingConfig: HikariConfig,
    tablesAutocreate: Boolean,
    query: QueryConfig
)

object SourcingConfig {

  /**
    * Defines parameters for streaming queries
    * @param batchSize
    *   the maximum number of elements to fetch with one query
    * @param refreshInterval
    *   the time to wait before reexecuting the query when the rows have all been consumed
    */
  final case class QueryConfig(batchSize: Int, refreshInterval: RefreshStrategy.Delay)

  final case class EvaluationConfig(maxDuration: FiniteDuration)
}
