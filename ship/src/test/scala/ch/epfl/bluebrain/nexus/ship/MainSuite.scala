package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class MainSuite extends NexusSuite {

  test("Show config") {
    Main.showConfig(None)
  }

}
