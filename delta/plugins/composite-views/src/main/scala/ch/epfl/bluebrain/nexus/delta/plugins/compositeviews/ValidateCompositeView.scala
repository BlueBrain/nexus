package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{CrossProjectSourceForbidden, CrossProjectSourceProjectNotFound, DuplicateIds, InvalidElasticSearchProjectionPayload, InvalidEncryptionSecrets, InvalidRemoteProjectSource, PermissionIsNotDefined, TooManyProjections, TooManySources, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewProjection, CompositeViewRejection, CompositeViewSource, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import monix.bio.{IO, UIO}

import java.util.UUID

/**
  * Validate an [[CompositeViewValue]] during command evaluation
  */
trait ValidateCompositeView {

  def apply(uuid: UUID, rev: Int, value: CompositeViewValue): IO[CompositeViewRejection, Unit]

}

object ValidateCompositeView {

  def apply(
      aclCheck: AclCheck,
      projects: Projects,
      fetchPermissions: UIO[Set[Permission]],
      client: ElasticSearchClient,
      deltaClient: DeltaClient,
      crypto: Crypto,
      prefix: String,
      maxSources: Int,
      maxProjections: Int
  )(implicit baseUri: BaseUri): ValidateCompositeView = (uuid: UUID, rev: Int, value: CompositeViewValue) => {
    def validateAcls(cpSource: CrossProjectSource): IO[CrossProjectSourceForbidden, Unit] =
      aclCheck.authorizeForOr(cpSource.project, events.read, cpSource.identities)(CrossProjectSourceForbidden(cpSource))

    def validateProject(cpSource: CrossProjectSource) = {
      projects.fetch(cpSource.project).mapError(_ => CrossProjectSourceProjectNotFound(cpSource)).void
    }

    def validateCrypto(token: Option[AccessToken]): IO[InvalidEncryptionSecrets.type, Unit] = token match {
      case Some(AccessToken(value)) =>
        IO.fromEither(crypto.encrypt(value.value).flatMap(crypto.decrypt).toEither.void)
          .mapError(_ => InvalidEncryptionSecrets)
      case None                     => IO.unit
    }

    def validatePermission(permission: Permission) =
      fetchPermissions.flatMap { perms =>
        IO.when(!perms.contains(permission))(IO.raiseError(PermissionIsNotDefined(permission)))
      }

    def validateIndex(es: ElasticSearchProjection, index: IndexLabel) =
      client
        .createIndex(index, Some(es.mapping), es.settings)
        .mapError {
          case err: HttpClientStatusError => InvalidElasticSearchProjectionPayload(err.jsonBody)
          case err                        => WrappedElasticSearchClientError(err)
        }
        .void

    val checkRemoteEvent: RemoteProjectSource => IO[HttpClientError, Unit] = deltaClient.checkEvents

    val validateSource: CompositeViewSource => IO[CompositeViewRejection, Unit] = {
      case _: ProjectSource             => IO.unit
      case cpSource: CrossProjectSource => validateAcls(cpSource) >> validateProject(cpSource)
      case rs: RemoteProjectSource      =>
        checkRemoteEvent(rs).mapError(InvalidRemoteProjectSource(rs, _)) >> validateCrypto(rs.token)
    }

    val validateProjection: CompositeViewProjection => IO[CompositeViewRejection, Unit] = {
      case sparql: SparqlProjection    => validatePermission(sparql.permission)
      case es: ElasticSearchProjection =>
        validatePermission(es.permission) >>
          validateIndex(es, CompositeViews.index(es, uuid, rev, prefix))
    }

    for {
      _          <- IO.raiseWhen(value.sources.value.size > maxSources)(TooManySources(value.sources.length, maxSources))
      _          <- IO.raiseWhen(value.projections.value.size > maxProjections)(
                      TooManyProjections(value.projections.length, maxProjections)
                    )
      allIds      = value.sources.value.toList.map(_.id) ++ value.projections.value.toList.map(_.id)
      distinctIds = allIds.distinct
      _          <- IO.raiseWhen(allIds.size != distinctIds.size)(DuplicateIds(allIds))
      _          <- value.sources.value.toList.foldLeftM(())((_, s) => validateSource(s))
      _          <- value.projections.value.toList.foldLeftM(())((_, s) => validateProjection(s))

    } yield ()

  }

}
