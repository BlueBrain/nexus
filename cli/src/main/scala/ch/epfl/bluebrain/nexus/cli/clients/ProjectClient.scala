package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.concurrent.Ref
import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.EnvConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, Console}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.client.Client
import org.http4s.{Headers, Request}

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
    * @param client  the underlying HTTP client
    * @param env     the CLI environment configuration
    * @param cache   an initial cache
    * @param console [[Console]] for logging.
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      env: EnvConfig,
      cache: Ref[F, Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]],
      console: Console[F]
  ): ProjectClient[F] = {
    implicit val c: Console[F] = console
    new LiveProjectClient[F](client, env, cache)
  }

  private class LiveProjectClient[F[_]: Timer: Console: Sync](
      client: Client[F],
      env: EnvConfig,
      cache: Ref[F, Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]]
  ) extends AbstractHttpClient[F](client, env)
      with ProjectClient[F] {

    override def labels(org: OrgUuid, proj: ProjectUuid): F[ClientErrOr[(OrgLabel, ProjectLabel)]] =
      cache.get.flatMap { map =>
        map.get((org, proj)) match {
          // value in cache, return
          case Some(value) => F.pure(Right(value))
          // value not in cache, fetch, update and return
          case None        =>
            get(org, proj).flatMap {
              // propagate error
              case l @ Left(_)      => F.pure(l)
              // success, update cache and return
              case r @ Right(value) =>
                cache.modify(m => (m.updated((org, proj), value), value)) *> F.pure(r)
            }
        }
      }

    private def get(org: OrgUuid, proj: ProjectUuid): F[ClientErrOr[(OrgLabel, ProjectLabel)]] = {
      val uri = env.project(org, proj)
      val req = Request[F](uri = uri, headers = Headers(env.authorizationHeader.toList))
      executeParse[NexusAPIProject](req).map {
        case Right(NexusAPIProject(orgLabel, projectLabel)) => Right((orgLabel, projectLabel))
        case Left(err)                                      => Left(err)
      }
    }
  }

  final private[ProjectClient] case class NexusAPIProject(`_organizationLabel`: OrgLabel, `_label`: ProjectLabel)
  private[ProjectClient] object NexusAPIProject {
    implicit val nexusAPIProjectDecoder: Decoder[NexusAPIProject] = deriveDecoder[NexusAPIProject]
  }
}
