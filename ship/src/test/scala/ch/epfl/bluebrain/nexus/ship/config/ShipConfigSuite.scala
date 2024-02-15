package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class ShipConfigSuite extends NexusSuite {

  test("Default configuration should be parsed and loaded") {
    ShipConfig.load(None).assert(_ => true)
  }
}
