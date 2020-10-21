package ch.epfl.bluebrain.nexus.delta.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * The service description.
  *
  * @param name the name of the service
  */
final case class DescriptionConfig(
    name: String
) {

  /**
    * @return the version of the service
    */
  val version: String = BuildInfo.version

  /**
    * @return the full name of the service (name + version)
    */
  val fullName: String = s"$name-${version.replaceAll("\\W", "-")}"
}

object DescriptionConfig {
  implicit final val descriptionConfigReader: ConfigReader[DescriptionConfig] =
    deriveReader[DescriptionConfig]
}
