package ch.epfl.bluebrain.nexus.cli.config.influx

import pureconfig.ConfigConvert
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveConvert

/**
  * Type to query configuration mapping.
  *
  * @param tpe         the type for which the query configuration is applied
  * @param query       the sparql query to execute for collecting information
  * @param measurement the influxDB line protocol measurement name
  * @param values      the influxDB line protocol values names
  * @param timestamp   the influxDB line protocol optional timestamp
  */
final case class TypeConfig(
    tpe: String,
    query: String,
    measurement: String,
    values: Set[String],
    timestamp: String
)
object TypeConfig {
  implicit val typeConfigProductHint: ProductHint[TypeConfig]     = ProductHint[TypeConfig] {
    case "tpe" => "type"
    case other => other
  }
  implicit final val typeConfigConvert: ConfigConvert[TypeConfig] =
    deriveConvert[TypeConfig]
}
