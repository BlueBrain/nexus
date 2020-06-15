package ch.epfl.bluebrain.nexus.cli.config

import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import org.http4s.Uri
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * EleasticSearch Client configuration
  *
  * @param endpoint the ElasticSearch v.7.x API endpoint
  */
final case class ElasticSearchConfig(endpoint: Uri)

object ElasticSearchConfig extends Codecs {
  implicit final val elasticSearchConfigConvert: ConfigConvert[ElasticSearchConfig] =
    deriveConvert[ElasticSearchConfig]
}
