package ch.epfl.bluebrain.nexus.delta.sdk

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Defines default values
  * @param name
  *   a default name
  * @param description
  *   a default description
  */
final case class Defaults(name: String, description: String)

object Defaults {
  implicit final val defaultsConfigReader: ConfigReader[Defaults] =
    deriveReader[Defaults]
}
