package ch.epfl.bluebrain.nexus.cli.postgres.config

import pureconfig.ConfigConvert
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveConvert

/**
  * Type to query configuration mapping.
  */
final case class TypeConfig(
    tpe: String,
    queries: List[QueryConfig]
)
object TypeConfig {
  implicit val typeConfigProductHint: ProductHint[TypeConfig] = ProductHint[TypeConfig] {
    case "tpe" => "type"
    case other => other
  }
  implicit final val typeConfigConvert: ConfigConvert[TypeConfig] =
    deriveConvert[TypeConfig]
}
