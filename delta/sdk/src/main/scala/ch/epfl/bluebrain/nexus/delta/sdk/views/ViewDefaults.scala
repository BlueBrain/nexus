package ch.epfl.bluebrain.nexus.delta.sdk.views

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Defines default view values
  * @param name
  *   the name of the default view
  * @param description
  *   the description of the default view
  */
final case class ViewDefaults(name: String, description: String)

object ViewDefaults {
  implicit final val batchConfigReader: ConfigReader[ViewDefaults] =
    deriveReader[ViewDefaults]
}
