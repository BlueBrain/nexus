package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.JsonObject
import io.circe.syntax.KeyOps

import java.time.Instant

class SseEventLogSuite extends NexusSuite with ConfigFixtures {

  implicit private val jo: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  private val project = ProjectRef.unsafe("org", "proj")

  private def makeElem(sseData: SseData) = Elem.SuccessElem(
    EntityType("Person"),
    nxv + "1",
    project,
    Instant.now(),
    Offset.at(5L),
    sseData,
    4
  )

  test("Should serialize to an Akka SSE") {
    val elem = makeElem(
      SseData("Person", None, JsonObject("name" := "John Doe"))
    )
    assertEquals(
      SseEventLog.toServerSentEvent(elem),
      ServerSentEvent("""{"name":"John Doe"}""", "Person", "5")
    )
  }
}
