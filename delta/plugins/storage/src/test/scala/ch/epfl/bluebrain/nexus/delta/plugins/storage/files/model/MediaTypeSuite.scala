package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

class MediaTypeSuite extends NexusSuite {

  test("Should deserialize legacy akka content types") {
    Json
      .fromString("text/plain; charset=UTF-8")
      .as[MediaType]
      .assertRight(
        MediaType.`text/plain`
      )
  }

}
