package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.concurrent.Ref
import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.ClientErrOr
import ch.epfl.bluebrain.nexus.cli.config.EnvConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import ch.epfl.bluebrain.nexus.cli.utils.Logging._
import io.chrisdavenport.log4cats.Logger
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{Headers, Request}
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

trait ProjectClient[F[_]] {

  /**
    * Exchanges the provided organization and project uuids with their labels.
    *
    * @param org  the organization UUID
    * @param proj the project UUID
    */
  def labels(org: OrgUuid, proj: ProjectUuid): F[ClientErrOr[(OrgLabel, ProjectLabel)]]

}

object ProjectClient {

  /**
    * Construct a [[ProjectClient]] to read project information from the Nexus API. The information is cached to avoid
    * unnecessary subsequent requests.
    *
    * @param client the underlying HTTP client
    * @param env    the CLI environment configuration
    * @param cache  an initial cache
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      env: EnvConfig,
      cache: Ref[F, Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]]
  ): ProjectClient[F] =
    new LiveProjectClient[F](client, env, cache)

  private class LiveProjectClient[F[_]: Timer](
      client: Client[F],
      env: EnvConfig,
      cache: Ref[F, Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]]
  )(implicit F: Sync[F])
      extends ProjectClient[F] {

    override def labels(org: OrgUuid, proj: ProjectUuid): F[ClientErrOr[(OrgLabel, ProjectLabel)]] =
      cache.get.flatMap { map =>
        map.get((org, proj)) match {
          // value in cache, return
          case Some(value) => F.pure(Right(value))
          // value not in cache, fetch, update and return
          case None =>
            get(org, proj).flatMap {
              // propagate error
              case l @ Left(_) => F.pure(l)
              // success, update cache and return
              case r @ Right(value) =>
                cache.modify(m => (m.updated((org, proj), value), value)) *> F.pure(r)
            }
        }
      }

    private val retry                                = env.httpClient.retry
    private val successCondition                     = retry.condition.notRetryFromEither[(OrgLabel, ProjectLabel)] _
    implicit private val retryPolicy: RetryPolicy[F] = retry.retryPolicy
    implicit private val logOnError: (ClientErrOr[(OrgLabel, ProjectLabel)], RetryDetails) => F[Unit] =
      (eitherErr, details) => Logger[F].info(s"Client error '$eitherErr'. Retry details: '$details'")

    private def get(org: OrgUuid, proj: ProjectUuid): F[ClientErrOr[(OrgLabel, ProjectLabel)]] = {
      val uri = env.project(org, proj)
      val req = Request[F](uri = uri, headers = Headers(env.authorizationHeader.toList))
      val resp: F[ClientErrOr[(OrgLabel, ProjectLabel)]] = client.fetch(req)(ClientError.errorOr { r =>
        r.attemptAs[NexusAPIProject].value.map {
          case Left(err)                                      => Left(SerializationError(err.message, "NexusAPIProject"))
          case Right(NexusAPIProject(orgLabel, projectLabel)) => Right((orgLabel, projectLabel))
        }
      })
      resp.retryingM(successCondition)
    }
  }

  final private[ProjectClient] case class NexusAPIProject(`_organizationLabel`: OrgLabel, `_label`: ProjectLabel)
  private[ProjectClient] object NexusAPIProject {
    implicit val nexusAPIProjectDecoder: Decoder[NexusAPIProject] = deriveDecoder[NexusAPIProject]
  }
}
