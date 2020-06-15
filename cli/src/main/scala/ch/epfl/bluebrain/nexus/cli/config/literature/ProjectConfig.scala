package ch.epfl.bluebrain.nexus.cli.config.literature

import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import org.http4s.Uri
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Literature Projection project configuration.
  *
  * @param types the collection of types to be selected for literature projection
  */
final case class ProjectConfig(types: Seq[Uri])

object ProjectConfig extends Codecs {

  implicit final val projectConfigConvert: ConfigConvert[ProjectConfig] =
    deriveConvert[ProjectConfig]
}
