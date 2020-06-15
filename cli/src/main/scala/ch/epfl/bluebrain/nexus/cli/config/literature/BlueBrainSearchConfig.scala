package ch.epfl.bluebrain.nexus.cli.config.literature

import ch.epfl.bluebrain.nexus.cli.config.literature.BlueBrainSearchConfig.ModelType
import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import org.http4s.Uri
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Blue Brain search API connectivity information.
  *
  * @param endpoint   the API endpoint
  * @param modelTypes a set of model types with its dimensions
  */
final case class BlueBrainSearchConfig(endpoint: Uri, modelTypes: List[ModelType])

object BlueBrainSearchConfig extends Codecs {

  final case class ModelType(name: String, dimensions: Int)

  object ModelType {
    implicit final val modelTypeConfigConvert: ConfigConvert[ModelType] =
      deriveConvert[ModelType]
  }

  implicit final val blueBrainSearchConfigConvert: ConfigConvert[BlueBrainSearchConfig] =
    deriveConvert[BlueBrainSearchConfig]
}
