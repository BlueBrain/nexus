package ch.epfl.bluebrain.nexus.cli

import cats.data.EitherT
import cats.data.EitherT._
import cats.effect.{Concurrent, Sync}
import ch.epfl.bluebrain.nexus.cli.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.types.Offset.Sequence
import ch.epfl.bluebrain.nexus.cli.types.{Event, EventEnvelope, Label, Offset}
import fs2.Stream
import org.http4s.ServerSentEvent.EventId
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers._

import scala.math.Ordering.Implicits._

trait EventStreamClient[F[_]] {

  /**
    * Fetch the event stream for all Nexus resources.
    *
    * @param lastEventId the optional starting event offset
    */
  def apply(lastEventId: Option[Offset]): Stream[F, EventEnvelope]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: Label, lastEventId: Option[Offset]): Stream[F, EventEnvelope]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization'' and ''project''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: Label, project: Label, lastEventId: Option[Offset]): Stream[F, EventEnvelope]
}

object EventStreamClient {

  private[cli] final class LiveEventStreamClient[F[_]](
      client: Client[F],
      projectClient: ProjectClient[F],
      config: NexusConfig
  )(implicit F: Concurrent[F])
      extends EventStreamClient[F] {

    private val endpoints = NexusEndpoints(config)

    private def buildStream(uri: Uri, lastEventId: Option[Offset]): Stream[F, EventEnvelope] = {
      val lastEventIdH = lastEventId.map[Header](id => `Last-Event-Id`(EventId(id.asString)))
      val req          = Request[F](uri = uri, headers = Headers(lastEventIdH.toList ++ config.authorizationHeader.toList))
      client
        .stream(req)
        .flatMap { resp =>
          resp.body.through(ServerSentEvent.decoder[F])
        }
        .mapAsync(1) { sse =>
          (for {
            offset <- fromEither[F](sse.id.flatMap(v => Offset(v.value)).toRight(SerializationError("Missing offset")))
            event  <- EitherT(Event(sse, projectClient))
          } yield EventEnvelope(offset, event)).value
        }
        // TODO: log errors
        .collect { case Right(event) => event }
    }

    def apply(lastEventId: Option[Offset]): Stream[F, EventEnvelope] =
      buildStream(endpoints.eventsUri, lastEventId)

    def apply(organization: Label, lastEventId: Option[Offset]): Stream[F, EventEnvelope] =
      buildStream(endpoints.eventsUri(organization), lastEventId)

    def apply(organization: Label, project: Label, lastEventId: Option[Offset]): Stream[F, EventEnvelope] =
      buildStream(endpoints.eventsUri(organization, project), lastEventId)
  }

  private[cli] final class TestEventStreamClient[F[_]: Sync](events: List[Event]) extends EventStreamClient[F] {

    private val offsetEvents     = events.zipWithIndex.map { case (ev, i) => EventEnvelope(Sequence(i + 1L), ev) }
    private val noOffset: Offset = Sequence(0L)

    private def eventsFrom(lastEventId: Option[Offset]): Seq[EventEnvelope] =
      offsetEvents.dropWhile(_.offset <= lastEventId.getOrElse(noOffset))

    def apply(lastEventId: Option[Offset]): Stream[F, EventEnvelope] =
      Stream.fromIterator(eventsFrom(lastEventId).iterator)

    def apply(organization: Label, lastEventId: Option[Offset]): Stream[F, EventEnvelope] =
      Stream.fromIterator(eventsFrom(lastEventId).filter(_.event.organization == organization).iterator)

    def apply(organization: Label, project: Label, lastEventId: Option[Offset]): Stream[F, EventEnvelope] =
      Stream.fromIterator(
        eventsFrom(lastEventId).filter(e => e.event.organization == organization && e.event.project == project).iterator
      )

  }

  /**
    * Construct an [[EventStreamClient]] to read the SSE from Nexus.
    *
    * @param client        the underlying HTTP client
    * @param projectClient the project client to convert UUIDs into Labels
    * @param config        the Nexus configuration
    */
  final def apply[F[_]: Concurrent](
      client: Client[F],
      projectClient: ProjectClient[F],
      config: NexusConfig
  ): EventStreamClient[F] =
    new LiveEventStreamClient(client, projectClient, config)
}
