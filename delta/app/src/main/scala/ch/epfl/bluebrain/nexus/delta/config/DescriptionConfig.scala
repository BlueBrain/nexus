package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import cats.syntax.all._
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * The service description.
  *
  * @param name the name of the service
  */
final case class DescriptionConfig(name: Name) {

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

  @nowarn("cat=unused")
  implicit private val nameReader: ConfigReader[Name] =
    ConfigReader.fromString(str => Name(str).leftMap(err => CannotConvert(str, "Name", err.getMessage)))

  implicit final val descriptionConfigReader: ConfigReader[DescriptionConfig] =
    deriveReader[DescriptionConfig]
}
