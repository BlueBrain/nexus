package ch.epfl.bluebrain.nexus.delta.sourcing.config

import com.zaxxer.hikari.HikariConfig

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
