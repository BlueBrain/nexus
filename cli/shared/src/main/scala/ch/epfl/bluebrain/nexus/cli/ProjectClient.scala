package ch.epfl.bluebrain.nexus.cli

import java.util.UUID

import cats.effect.concurrent.Ref
import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.error.ClientError
import ch.epfl.bluebrain.nexus.cli.error.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.types.Label
import io.chrisdavenport.log4cats.Logger
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{Headers, Request, Status}
import retry.CatsEffect._
import retry._
import retry.syntax.all._

trait ProjectClient[F[_]] {

  /**
    * Fetches the labels from the passed organization and projects UUIDs.
    *
    * @param organization the organization UUID
    * @param project      the project UUID
    */
  def label(organization: UUID, project: UUID): F[ClientErrOr[ProjectLabelRef]]

}

object ProjectClient {
  type UUIDToLabel = Map[ProjectUuidRef, ProjectLabelRef]

  final private[cli] class LiveProjectClient[F[_]: Timer: Sync](
      client: Client[F],
      config: NexusConfig,
      cache: Ref[F, UUIDToLabel]
  ) extends ProjectClient[F] {
    private val F: Sync[F]                           = Sync[F]
    private val retry                                = config.httpClient.retry
    private val endpoints                            = NexusEndpoints(config)
    private val successCondition                     = retry.retryCondition.notRetryFromEither[ProjectLabelRef] _
    implicit private val retryPolicy: RetryPolicy[F] = retry.retryPolicy
    implicit private val logOnError: (ClientErrOr[ProjectLabelRef], RetryDetails) => F[Unit] =
      (eitherErr, details) => Logger[F].info(s"Client error '$eitherErr'. Retry details: '$details'")

    def label(organization: UUID, project: UUID): F[ClientErrOr[ProjectLabelRef]] =
      cache.get.flatMap {
        case uuidToLabel if uuidToLabel.contains((organization, project)) =>
          F.pure(Right(uuidToLabel((organization, project))))
        case _ =>
          val uri = endpoints.projectUri(organization, project)
          val req = Request[F](uri = uri, headers = Headers(config.authorizationHeader.toList))
          val resp: F[ClientErrOr[ProjectLabelRef]] = client.fetch(req)(ClientError.errorOr { r =>
            r.attemptAs[NexusAPIProject].value.flatMap {
              case Left(err) => F.pure(Left(SerializationError(err.message, "NexusAPIProject")))
              case Right(NexusAPIProject(orgLabel, projectLabel)) =>
                cache.update(_ + ((organization, project) -> ((orgLabel, projectLabel)))) >>
                  F.pure(Right((orgLabel, projectLabel)))
            }
          })
          resp.retryingM(successCondition)
      }
  }

  final private[cli] class TestProjectClient[F[_]](cache: UUIDToLabel)(implicit F: Sync[F]) extends ProjectClient[F] {
    private val notFound: ClientError = ClientError.unsafe(Status.NotFound, "Project not found")
    def label(organization: UUID, project: UUID): F[ClientErrOr[ProjectLabelRef]] =
      F.delay(cache.get((organization, project)).toRight(notFound))
  }

  /**
    * Construct a [[ProjectClient]] to read the project information from Nexus.
    * This method start with an empty, so that every project that is fetched once is cached and not fetched again.
    *
    * @param client the underlying HTTP client
    * @param config the Nexus configuration
    */
  final def apply[F[_]: Sync: Timer](client: Client[F], config: NexusConfig): F[ProjectClient[F]] =
    Ref[F].of(Map.empty[ProjectUuidRef, ProjectLabelRef]).map(cache => apply(client, config, cache))

  final private[cli] def apply[F[_]: Sync: Timer](
      client: Client[F],
      config: NexusConfig,
      cache: Ref[F, UUIDToLabel]
  ): ProjectClient[F] =
    new LiveProjectClient(client, config, cache)

  final private[ProjectClient] case class NexusAPIProject(`_organizationLabel`: Label, `_label`: Label)

  object NexusAPIProject {
    implicit private[ProjectClient] val nexusAPIProjectDecoder: Decoder[NexusAPIProject] =
      deriveDecoder[NexusAPIProject]
  }
}
