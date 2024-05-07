package ch.epfl.bluebrain.nexus.ship.config

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.configurable._
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

final case class OrganizationCreationConfig(values: Map[Label, String])

object OrganizationCreationConfig {

  implicit final val quotasConfigReader: ConfigReader[OrganizationCreationConfig] = {
    implicit val mapReader: ConfigReader[Map[Label, String]] =
      genericMapReader(str => Label(str).leftMap(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage)))
    deriveReader[OrganizationCreationConfig]
  }

}
