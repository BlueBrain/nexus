package ch.epfl.bluebrain.nexus.cli.postgres.config

import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Table, ddl and sparql query configuration.
  */
final case class QueryConfig(
    table: String,
    ddl: String,
    query: String
)
object QueryConfig {
  implicit final val queryConfigConvert: ConfigConvert[QueryConfig] =
    deriveConvert[QueryConfig]
}
