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
import io.circe.syntax.EncoderOps

import java.time.Instant

class SseEventLogSuite extends NexusSuite with ConfigFixtures {

  implicit private val jo: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  private val ref = ProjectRef.unsafe("org", "proj")

  private def makeSuccessElem(sseData: SseData) = Elem.SuccessElem(
    EntityType("Person"),
    nxv + "1",
    None,
    Instant.now(),
    Offset.at(5L),
    sseData,
    4
  )

  test("Should not inject project uuids") {
    val elem = makeSuccessElem(
      SseData("Person", None, JsonObject("name" -> "John Doe".asJson))
    )
    assertEquals(
      SseEventLog.toServerSentEvent(elem),
      ServerSentEvent("""{"name":"John Doe"}""", "Person", "5")
    )
  }

  test("Should not inject project uuids when the ref is unknown") {
    val elem = Elem.SuccessElem(
      EntityType("Person"),
      nxv + "1",
      None,
      Instant.now(),
      Offset.at(5L),
      SseData("Person", Some(ProjectRef.unsafe("xxx", "xxx")), JsonObject("name" -> "John Doe".asJson)),
      4
    )
    assertEquals(
      SseEventLog.toServerSentEvent(elem),
      ServerSentEvent("""{"name":"John Doe"}""", "Person", "5")
    )
  }

  test("Should inject project uuids when the ref is unknown") {
    val elem = Elem.SuccessElem(
      EntityType("Person"),
      nxv + "1",
      None,
      Instant.now(),
      Offset.at(5L),
      SseData("Person", Some(ref), JsonObject("name" -> "John Doe".asJson)),
      4
    )
    assertEquals(
      SseEventLog.toServerSentEvent(elem),
      ServerSentEvent(
        s"""{"name":"John Doe"}""",
        "Person",
        "5"
      )
    )
  }
}
