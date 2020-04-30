package ch.epfl.bluebrain.nexus.cli.config.postgres

import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import org.http4s.Uri
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * SQL Projection project configuration.
  *
  * @param sparqlView an optional view reference to query
  * @param types      the collection of type specific configuration
  */
final case class ProjectConfig(
    sparqlView: Option[Uri],
    types: List[TypeConfig]
)

object ProjectConfig extends Codecs {

  implicit final val projectConfigConvert: ConfigConvert[ProjectConfig] =
    deriveConvert[ProjectConfig]
}
