package ch.epfl.bluebrain.nexus.delta.sdk.sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import monix.bio.UIO

import java.time.Instant
import java.util.UUID

class SseEventLogSuite extends BioSuite with ConfigFixtures with IOFixedClock {

  implicit private val jo: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  private val ref = ProjectRef.unsafe("org", "proj")

  private val orgUuid     = UUID.randomUUID()
  private val projectUuid = UUID.randomUUID()

  private def fetchUuids: ProjectRef => UIO[Option[(UUID, UUID)]] = {
    case `ref` => UIO.some(orgUuid -> projectUuid)
    case _     => UIO.none
  }

  private def makeEnvelope(sseData: SseData) = Envelope(
    EntityType("Person"),
    "1",
    4,
    sseData,
    Instant.now(),
    Offset.at(5L)
  )

  test("Should not inject project uuids") {
    val envelope = makeEnvelope(
      SseData("Person", None, JsonObject("name" -> "John Doe".asJson))
    )
    SseEventLog
      .toServerSentEvent(envelope, fetchUuids)
      .assert(ServerSentEvent("""{"name":"John Doe"}""", "Person", "5"))
  }

  test("Should not inject project uuids when the ref is unknown") {
    val envelope = Envelope(
      EntityType("Person"),
      "1",
      4,
      SseData("Person", Some(ProjectRef.unsafe("xxx", "xxx")), JsonObject("name" -> "John Doe".asJson)),
      Instant.now(),
      Offset.at(5L)
    )
    SseEventLog
      .toServerSentEvent(envelope, fetchUuids)
      .assert(ServerSentEvent("""{"name":"John Doe"}""", "Person", "5"))
  }

  test("Should inject project uuids when the ref is unknown") {
    val envelope = Envelope(
      EntityType("Person"),
      "1",
      4,
      SseData("Person", Some(ref), JsonObject("name" -> "John Doe".asJson)),
      Instant.now(),
      Offset.at(5L)
    )
    SseEventLog
      .toServerSentEvent(envelope, fetchUuids)
      .assert(
        ServerSentEvent(
          s"""{"_organizationUuid":"$orgUuid","_projectUuid":"$projectUuid","name":"John Doe"}""",
          "Person",
          "5"
        )
      )
  }
}
