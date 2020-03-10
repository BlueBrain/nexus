package ch.epfl.bluebrain.nexus.cli

import cats.data.EitherT
import cats.data.EitherT._
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.EventStreamClient.EventStream
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.error.ClientError
import ch.epfl.bluebrain.nexus.cli.error.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.types.Offset.Sequence
import ch.epfl.bluebrain.nexus.cli.types.{Event, Label, Offset}
import fs2.{Pipe, Stream}
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
  def apply(lastEventId: Option[Offset]): F[EventStream[F]]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: Label, lastEventId: Option[Offset]): F[EventStream[F]]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization'' and ''project''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: Label, project: Label, lastEventId: Option[Offset]): F[EventStream[F]]
}

object EventStreamClient {

  trait EventStream[F[_]] {

    /**
      * the Stream of events
      */
    def value: Stream[F, Event]

    /**
      * the eventId for the last consumed event
      */
    def currentEventId(): F[Option[Offset]]
  }

  object EventStream {
    def apply[F[_]](stream: Stream[F, Event], ref: Ref[F, Option[Offset]]): EventStream[F] = new EventStream[F] {
      override def value: Stream[F, Event]             = stream
      override def currentEventId(): F[Option[Offset]] = ref.get
    }
  }

  private[cli] final class LiveEventStreamClient[F[_]](
      client: Client[F],
      projectClient: ProjectClient[F],
      config: NexusConfig
  )(implicit F: Concurrent[F])
      extends EventStreamClient[F] {

    private val endpoints = NexusEndpoints(config)
    private lazy val offsetError =
      SerializationError("The expected offset was not found or had the wrong format", "Offset")

    private def buildStream(uri: Uri, lastEventIdCache: Ref[F, Option[Offset]]): F[EventStream[F]] =
      lastEventIdCache.get
        .map { lastEventId =>
          val lastEventIdH = lastEventId.map[Header](id => `Last-Event-Id`(EventId(id.asString)))
          val req          = Request[F](uri = uri, headers = Headers(lastEventIdH.toList ++ config.authorizationHeader.toList))
          client
            .stream(req)
            .flatMap(_.body.through(ServerSentEvent.decoder[F]))
            .evalMap { sse =>
              (for {
                off   <- fromEither[F](sse.id.flatMap(v => Offset(v.value)).toRight(offsetError))
                _     <- right[ClientError](lastEventIdCache.update(_ => Some(off)))
                event <- EitherT(Event(sse, projectClient))
              } yield event).value
            }
            // TODO: log errors
            .collect { case Right(event) => event }
        }
        .map(stream => EventStream(stream, lastEventIdCache))

    def apply(lastEventId: Option[Offset]): F[EventStream[F]] =
      Ref.of(lastEventId).flatMap(ref => buildStream(endpoints.eventsUri, ref))

    def apply(organization: Label, lastEventId: Option[Offset]): F[EventStream[F]] =
      Ref.of(lastEventId).flatMap(ref => buildStream(endpoints.eventsUri(organization), ref))

    def apply(organization: Label, project: Label, lastEventId: Option[Offset]): F[EventStream[F]] =
      Ref.of(lastEventId).flatMap(ref => buildStream(endpoints.eventsUri(organization, project), ref))

  }

  private[cli] final class TestEventStreamClient[F[_]](events: List[Event])(implicit F: Sync[F])
      extends EventStreamClient[F] {

    private val offsetEvents: Seq[(Offset, Event)] = events.zipWithIndex.map { case (ev, i) => (Sequence(i + 1L), ev) }
    private val noOffset: Offset                   = Sequence(0L)

    private def eventsFrom(lastEventIdCache: Ref[F, Option[Offset]]) =
      lastEventIdCache.get.map(
        lastEventId => offsetEvents.dropWhile { case (offset, _) => offset <= lastEventId.getOrElse(noOffset) }
      )

    private def saveOffset(lastEventIdCache: Ref[F, Option[Offset]]): Pipe[F, (Offset, Event), Event] =
      _.evalMap { case (offset, event) => lastEventIdCache.update(_ => Some(offset)) >> F.pure(event) }

    def apply(lastEventId: Option[Offset]): F[EventStream[F]] =
      for {
        ref    <- Ref.of(lastEventId)
        events <- eventsFrom(ref)
      } yield EventStream(Stream.fromIterator(events.iterator).through(saveOffset(ref)), ref)

    def apply(organization: Label, lastEventId: Option[Offset]): F[EventStream[F]] =
      for {
        ref    <- Ref.of(lastEventId)
        events <- eventsFrom(ref)
        filtered = events.iterator.filter { case (_, ev) => ev.organization == organization }
      } yield EventStream(Stream.fromIterator(filtered.iterator).through(saveOffset(ref)), ref)

    def apply(organization: Label, project: Label, lastEventId: Option[Offset]): F[EventStream[F]] =
      for {
        ref    <- Ref.of(lastEventId)
        events <- eventsFrom(ref)
        filtered = events.iterator.filter { case (_, ev) => ev.organization == organization && ev.project == project }
      } yield EventStream(Stream.fromIterator(filtered.iterator).through(saveOffset(ref)), ref)

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
