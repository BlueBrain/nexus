package ch.epfl.bluebrain.nexus.cli.clients

import cats.data.EitherT
import cats.data.EitherT.{fromEither, right}
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.ClientRetryCondition.{Always, OnServerError}
import ch.epfl.bluebrain.nexus.cli.config.EnvConfig
import ch.epfl.bluebrain.nexus.cli.sse.{Event, EventStream, Offset, OrgLabel, ProjectLabel}
import io.circe.parser._
import org.http4s.ServerSentEvent.EventId
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.`Last-Event-Id`

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
  def apply(organization: OrgLabel, lastEventId: Option[Offset]): F[EventStream[F]]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization'' and ''project''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: OrgLabel, project: ProjectLabel, lastEventId: Option[Offset]): F[EventStream[F]]
}

object EventStreamClient {

  final def apply[F[_]: Concurrent](
      client: Client[F],
      projectClient: ProjectClient[F],
      env: EnvConfig
  ): EventStreamClient[F] =
    new LiveEventStreamClient[F](client, projectClient, env)

  private class LiveEventStreamClient[F[_]](
      client: Client[F],
      projectClient: ProjectClient[F],
      env: EnvConfig
  )(implicit F: Concurrent[F])
      extends EventStreamClient[F] {

    private val retry            = env.httpClient.retry
    private lazy val offsetError =
      SerializationError("The expected offset was not found or had the wrong format", "Offset")

    private def decodeEvent(str: String): Either[ClientError, Event] =
      decode[Event](str).leftMap(err => SerializationError(err.getMessage, "NexusAPIEvent", Some(str)))

    private def buildStream(uri: Uri, lastEventIdCache: Ref[F, Option[Offset]]): F[EventStream[F]] = {
      val streamF = lastEventIdCache.get
        .map { lastEventId =>
          val lastEventIdH = lastEventId.map[Header](id => `Last-Event-Id`(EventId(id.asString)))
          val req          = Request[F](uri = uri, headers = Headers(lastEventIdH.toList ++ env.authorizationHeader.toList))
          client
            .stream(req)
            .evalMap[F, Response[F]] {
              case r if retry.condition == Always && !r.status.isSuccess                                             =>
                F.raiseError(ClientError.unsafe(r.status, "Error when fetching SSEs"))
              case r if retry.condition == OnServerError && !r.status.isSuccess && r.status != Status.GatewayTimeout =>
                F.raiseError(ClientError.unsafe(r.status, "Error when fetching SSEs"))
              case r                                                                                                 => F.pure(r)
            }
            .flatMap(_.body.through(ServerSentEvent.decoder[F]))
            .collect { case ServerSentEvent(data, _, Some(id), _) => id -> data }
            .evalMap { case (id, data) =>
              val resultT = for {
                off        <- fromEither[F](Offset(id.value).toRight[ClientError](offsetError))
                _          <- right[ClientError](lastEventIdCache.update(_ => Some(off)))
                event      <- fromEither[F](decodeEvent(data))
                labels     <- EitherT(projectClient.labels(event.organization, event.project))
                (org, proj) = labels
              } yield (event, org, proj)
              resultT.value
            }
        }
      F.delay(EventStream(streamF, lastEventIdCache))
    }

    def apply(lastEventId: Option[Offset]): F[EventStream[F]] =
      Ref.of(lastEventId).flatMap(ref => buildStream(env.eventsUri, ref))

    def apply(organization: OrgLabel, lastEventId: Option[Offset]): F[EventStream[F]] =
      Ref.of(lastEventId).flatMap(ref => buildStream(env.eventsUri(organization), ref))

    def apply(organization: OrgLabel, project: ProjectLabel, lastEventId: Option[Offset]): F[EventStream[F]] =
      Ref.of(lastEventId).flatMap(ref => buildStream(env.eventsUri(organization, project), ref))

  }

}
