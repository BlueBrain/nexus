package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, Defaults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

trait ShipConfigFixtures extends ConfigFixtures {

  private val baseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))

  private val organizationsCreation = OrganizationCreationConfig(
    Map(Label.unsafe("public") -> "The public organization", Label.unsafe("obp") -> "The OBP organization")
  )

  private val viewDefaults = ViewDefaults(
    Defaults("Default ES View", "Description ES View"),
    Defaults("Default EBG View", "Description BG View")
  )

  private val serviceAccount: ServiceAccountConfig = ServiceAccountConfig(
    ServiceAccount(User("internal", Label.unsafe("sa")))
  )

  def inputConfig: InputConfig =
    InputConfig(baseUri, eventLogConfig, organizationsCreation, Map.empty, viewDefaults, serviceAccount)

}
