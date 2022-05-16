package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import com.zaxxer.hikari.HikariConfig

final case class SourcingConfig(
    readConfig: HikariConfig,
    writeConfig: HikariConfig,
    tablesAutocreate: Boolean,
    query: QueryConfig
)

object SourcingConfig {

  final case class QueryConfig(batchSize: Int, refreshInterval: RefreshStrategy.Delay)

}
