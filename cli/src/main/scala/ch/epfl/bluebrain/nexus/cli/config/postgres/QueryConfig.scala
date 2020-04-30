package ch.epfl.bluebrain.nexus.cli.config.postgres

import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Table, ddl and sparql query configuration.
  *
  * @param table the table to be used for upserting data
  * @param ddl   the query used to create the table
  * @param query the sparql query to execute for collecting information
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
