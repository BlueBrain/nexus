package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class OrganizationCreationConfig(values: Map[Label, String])

object OrganizationCreationConfig {

  implicit final val organizationCreationConfigReader: ConfigReader[OrganizationCreationConfig] = {
    implicit val mapReader: ConfigReader[Map[Label, String]] = Label.labelMapReader[String]
    deriveReader[OrganizationCreationConfig]
  }

}
