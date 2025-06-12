package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.{Pull, Stream}
import org.http4s.Method.GET
import org.http4s.ServerSentEvent.EventId
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.headers.{`Last-Event-Id`, Accept}
import org.http4s.{EventStream, Header, MediaType, ServerSentEvent, Uri}

import scala.concurrent.duration.FiniteDuration

object SseClient {

  private val logger = Logger[SseClient.type]

  private val accept = Accept(MediaType.`text/event-stream`)

  def lastEventId(offset: Offset): IO[`Last-Event-Id`] =
    IO.fromEither(`Last-Event-Id`.parse(offset.value.toString))

  private def log(message: => String) =
    Stream.eval(logger.debug(message))

  def apply(client: Client[IO], retryDelay: FiniteDuration, uri: Uri, offset: Offset): EventStream[IO] = {

    def go(stream: EventStream[IO], lastEventId: EventId): Pull[IO, ServerSentEvent, Unit] =
      stream.pull.uncons.flatMap {
        case Some((events, tail)) =>
          Pull.output(events) >> go(tail, events.last.flatMap(_.id).getOrElse(lastEventId))
        case None                 =>
          Pull.eval(IO.sleep(retryDelay)) >>
            resume(lastEventId)
      }

    def resume(eventId: EventId): Pull[IO, ServerSentEvent, Unit] =
      go(
        log(s"All events have been consumed, waiting for ${retryDelay.toSeconds} before resuming") >>
          Stream.sleep[IO](retryDelay) >>
          newStream(eventId),
        eventId
      )

    def newStream(eventId: EventId) = {
      val headers: Vector[Header.ToRaw] = Vector(accept, `Last-Event-Id`(eventId))
      val request                       = GET(uri, headers)
      client
        .stream(request)
        .flatMap(_.body)
        .through(ServerSentEvent.decoder[IO])
    }

    val eventId = EventId(offset.value.toString)

    go(
      log(s"Starting consuming events from $uri") >>
        newStream(eventId),
      eventId
    ).stream
  }

}
