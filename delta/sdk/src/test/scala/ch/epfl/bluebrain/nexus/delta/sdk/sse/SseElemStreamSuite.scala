package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import munit.FunSuite

import java.time.Instant

class SseElemStreamSuite extends FunSuite {

  test("Serialize an elem") {
    val elem = SuccessElem(
      Resources.entityType,
      iri"https://bbp.epfl.ch/my-resource",
      ProjectRef.unsafe("org", "proj"),
      Instant.EPOCH,
      Offset.at(42L),
      (),
      5
    )

    assertEquals(
      SseElemStream.toServerSentEvent(elem),
      ServerSentEvent(
        """{"tpe":"resource","id":"https://bbp.epfl.ch/my-resource","project":"org/proj","instant":"1970-01-01T00:00:00Z","offset":{"value":42,"@type":"At"},"value":{},"rev":5,"@type":"SuccessElem"}""",
        "Success",
        "42"
      )
    )
  }

}
