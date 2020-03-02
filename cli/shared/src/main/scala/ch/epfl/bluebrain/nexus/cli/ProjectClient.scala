package ch.epfl.bluebrain.nexus.cli

import java.util.UUID

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.ProjectClient.ProjectLabelRef
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.types.Label
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{Headers, Request}

trait ProjectClient[F[_]] {

  /**
    * Fetches the labels from the passed organization and projects UUIDs.
    *
    * @param organization the organization UUID
    * @param project      the project UUID
    */
  def label(organization: UUID, project: UUID): F[Either[ClientError, ProjectLabelRef]]

}

object ProjectClient {
  type ProjectLabelRef = (Label, Label)
  type UUIDToLabel     = Map[(UUID, UUID), ProjectLabelRef]

  /**
    * Construct a [[ProjectClient]] to read the project information from Nexus.
    * This method start with an empty, so that every project that is fetched once is cached and not fetched again.
    *
    * @param client the underlying HTTP client
    * @param config the Nexus configuration
    * @tparam F the effect type
    */
  final def apply[F[_]](client: Client[F], config: NexusConfig)(implicit F: Sync[F]): F[ProjectClient[F]] =
    Ref[F].of(Map.empty[(UUID, UUID), ProjectLabelRef]).map(cache => apply(client, config, cache))

  /**
    * Construct a [[ProjectClient]] to read the project information from Nexus.
    * This client uses a cache, so that every project that is fetched once is cached and not fetched again.
    *
    * @param client the underlying HTTP client
    * @param config the Nexus configuration
    * @param cache  the cached UUIDs
    * @tparam F the effect type
    */
  final def apply[F[_]](
      client: Client[F],
      config: NexusConfig,
      cache: Ref[F, UUIDToLabel]
  )(implicit F: Sync[F]): ProjectClient[F] =
    new ProjectClient[F] {

      private val endpoints = NexusEndpoints(config)

      def label(organization: UUID, project: UUID): F[Either[ClientError, ProjectLabelRef]] = {
        cache.get.flatMap {
          case uuidToLabel if uuidToLabel.contains((organization, project)) =>
            F.pure(Right(uuidToLabel((organization, project))))
          case _ =>
            val uri = endpoints.projectUri(organization, project)
            val req = Request[F](uri = uri, headers = Headers(config.authorizationHeader.toList))
            client.fetch(req)(ClientError.errorOr { r =>
              r.attemptAs[NexusAPIProject].value.flatMap {
                case Left(err) => F.pure(Left(SerializationError(err.message)))
                case Right(NexusAPIProject(orgLabel, projectLabel)) =>
                  cache.update(_ + ((organization, project) -> ((orgLabel, projectLabel)))) >>
                    F.pure(Right((orgLabel, projectLabel)))
              }
            })
        }
      }
    }

  final private[ProjectClient] case class NexusAPIProject(`_organizationLabel`: Label, `_label`: Label)

  object NexusAPIProject {
    private[ProjectClient] implicit val nexusAPIProjectDecoder: Decoder[NexusAPIProject] =
      deriveDecoder[NexusAPIProject]
  }
}
