package ch.epfl.bluebrain.nexus.delta.sdk.fusion

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.instances.*
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Fusion configuration
  * @param base
  *   the base url of fusion
  * @param enableRedirects
  *   enables redirections to Fusion if the `Accept` header is set to `text/html`
  * @param resolveBase
  *   base to use to reconstruct resource identifiers in the resolve proxy pass
  */
final case class FusionConfig(base: Uri, enableRedirects: Boolean, resolveBase: Uri)

object FusionConfig {
  implicit final val fusionConfigReader: ConfigReader[FusionConfig] =
    deriveReader[FusionConfig]
}
