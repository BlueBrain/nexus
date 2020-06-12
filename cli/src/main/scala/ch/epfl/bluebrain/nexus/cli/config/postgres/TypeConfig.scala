package ch.epfl.bluebrain.nexus.cli.config.postgres

import pureconfig.ConfigConvert
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveConvert

/**
  * Type to query configuration mapping.
  *
  * @param tpe     the type for which the query configuration is applied
  * @param queries the collection of query configurations for this type
  */
final case class TypeConfig(
    tpe: String,
    queries: List[QueryConfig]
)
object TypeConfig {
  implicit val typeConfigProductHint: ProductHint[TypeConfig]     = ProductHint[TypeConfig] {
    case "tpe" => "type"
    case other => other
  }
  implicit final val typeConfigConvert: ConfigConvert[TypeConfig] =
    deriveConvert[TypeConfig]
}
