package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.JsonObject
import io.circe.syntax.EncoderOps

import java.time.Instant

class SseEventLogSuite extends NexusSuite with ConfigFixtures {

  implicit private val jo: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  private val ref = ProjectRef.unsafe("org", "proj")

  private def makeEnvelope(sseData: SseData) = Envelope(
    EntityType("Person"),
    nxv + "1",
    4,
    sseData,
    Instant.now(),
    Offset.at(5L)
  )

  test("Should not inject project uuids") {
    val envelope = makeEnvelope(
      SseData("Person", None, JsonObject("name" -> "John Doe".asJson))
    )
    assertEquals(
      SseEventLog.toServerSentEvent(envelope),
      ServerSentEvent("""{"name":"John Doe"}""", "Person", "5")
    )
  }

  test("Should not inject project uuids when the ref is unknown") {
    val envelope = Envelope(
      EntityType("Person"),
      nxv + "1",
      4,
      SseData("Person", Some(ProjectRef.unsafe("xxx", "xxx")), JsonObject("name" -> "John Doe".asJson)),
      Instant.now(),
      Offset.at(5L)
    )
    assertEquals(
      SseEventLog.toServerSentEvent(envelope),
      ServerSentEvent("""{"name":"John Doe"}""", "Person", "5")
    )
  }

  test("Should inject project uuids when the ref is unknown") {
    val envelope = Envelope(
      EntityType("Person"),
      nxv + "1",
      4,
      SseData("Person", Some(ref), JsonObject("name" -> "John Doe".asJson)),
      Instant.now(),
      Offset.at(5L)
    )
    assertEquals(
      SseEventLog.toServerSentEvent(envelope),
      ServerSentEvent(
        s"""{"name":"John Doe"}""",
        "Person",
        "5"
      )
    )
  }
}
