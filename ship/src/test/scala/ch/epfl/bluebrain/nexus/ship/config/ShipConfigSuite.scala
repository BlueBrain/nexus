package ch.epfl.bluebrain.nexus.ship.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.Path

class ShipConfigSuite extends NexusSuite {

  test("Default configuration should be parsed and loaded") {
    val expectedBaseUri = BaseUri("http://localhost:8080", Label.unsafe("v1"))
    ShipConfig.load(None).map(_.baseUri).assertEquals(expectedBaseUri)
  }

  test("Default configuration should be overloaded by the external config") {
    val expectedBaseUri = BaseUri("https://bbp.epfl.ch", Label.unsafe("v1"))
    for {
      externalConfigPath <- loader.absolutePath("config/external.conf")
      _                  <- ShipConfig.load(Some(Path(externalConfigPath))).map(_.baseUri).assertEquals(expectedBaseUri)
    } yield ()
  }
}
