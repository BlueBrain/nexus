package ch.epfl.bluebrain.nexus.cli

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.ClientError.ServerStatusError
import ch.epfl.bluebrain.nexus.cli.types.Offset.{Sequence, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.cli.types.{Event, EventEnvelope, Label, Offset}
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import fs2.Stream
import fs2.text.utf8Encode
import org.http4s.Method._
import org.http4s.ServerSentEvent.EventId
import org.http4s.client.Client
import org.http4s.headers.{`Last-Event-Id`, Authorization}
import org.http4s.{HttpApp, Response, Status, Uri}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class EventStreamClientSpec extends AnyWordSpecLike with Matchers with Fixtures {

  private val orgUuid                  = UUID.fromString("e6a5c668-5051-49bb-9414-265ccb51323e")
  private val orgLabel                 = Label("myorg")
  private val projectUuid              = UUID.fromString("0e23f7e4-0d06-4e05-b850-5944f03557af")
  private val projectLabel             = Label("mylabel")
  private val stream: Stream[IO, Byte] = Stream.emit(contentOf("/events.txt")).through(utf8Encode)
  private val eventId: Offset          = Sequence(1L)

  private val mockedHttpApp = HttpApp[IO] {
    case r
        if (r.uri == endpoints.eventsUri ||
          r.uri == endpoints.eventsUri(orgLabel) ||
          r.uri == endpoints.eventsUri(orgLabel, projectLabel)) &&
          r.method == GET &&
          r.headers.get(Authorization) == config.authorizationHeader &&
          r.headers.get(`Last-Event-Id`).contains(`Last-Event-Id`(EventId(eventId.asString))) =>
      IO.delay(Response[IO](Status.Ok).withEntity(stream))
  }

  private val mockedHttpClient: Client[IO] = Client.fromHttpApp(mockedHttpApp)

  private val projectClient = new ProjectClient[IO] {
    override def label(organization: UUID, project: UUID): IO[Either[ClientError, (Label, Label)]] =
      if (orgUuid == organization && project == projectUuid) IO.pure(Right(orgLabel -> projectLabel))
      else IO.pure(Left(ServerStatusError(Status.InternalServerError, "")))
  }

  private val client: EventStreamClient[IO] = EventStreamClient(mockedHttpClient, projectClient, config)

  "An EventStreamClient" should {

    "return events" in {
      val resourceId        = Uri.unsafeFromString("https://example.com/v1/myId")
      val timeCreated       = TimeBasedUUID(UUID.fromString("a2293500-5c75-11ea-beb1-a5eb66b44d1c"))
      val instantCreated    = Instant.parse("2020-03-02T11:04:48.934789Z")
      val timeUpdated       = TimeBasedUUID(UUID.fromString("b8a93f50-5c75-11ea-beb1-a5eb66b44d1c"))
      val instantUpdated    = Instant.parse("2020-03-02T11:05:26.713124Z")
      val timeDeprecated    = TimeBasedUUID(UUID.fromString("bd62f6d0-5c75-11ea-beb1-a5eb66b44d1c"))
      val instantDeprecated = Instant.parse("2020-03-02T11:05:34.643363Z")
      val expected = Vector(
        EventEnvelope(
          timeCreated,
          Event("Created", resourceId, orgLabel, projectLabel, Set(nxv / "TypeA", nxv / "TypeB"), instantCreated)
        ),
        EventEnvelope(
          timeUpdated,
          Event("Updated", resourceId, orgLabel, projectLabel, Set(nxv / "TypeB"), instantUpdated)
        ),
        EventEnvelope(
          timeDeprecated,
          Event("Deprecated", resourceId, orgLabel, projectLabel, Set.empty, instantDeprecated)
        )
      )

      val emptyV = Vector.empty[EventEnvelope]

      client(Some(eventId)).take(3).compile.fold(emptyV)(_ :+ _).unsafeRunSync() shouldEqual expected

      client(orgLabel, Some(eventId)).take(3).compile.fold(emptyV)(_ :+ _).unsafeRunSync() shouldEqual expected

      client(orgLabel, projectLabel, Some(eventId)).take(3).compile.fold(emptyV)(_ :+ _).unsafeRunSync() shouldEqual
        expected
    }
  }
}
