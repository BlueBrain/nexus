package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.projectionIndex
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{CrossProjectSourceForbidden, CrossProjectSourceProjectNotFound, DuplicateIds, InvalidElasticSearchProjectionPayload, InvalidRemoteProjectSource, PermissionIsNotDefined, TooManyProjections, TooManySources, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewProjection, CompositeViewSource, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects

import java.util.UUID

/**
  * Validate an [[CompositeViewValue]] during command evaluation
  */
trait ValidateCompositeView {

  def apply(uuid: UUID, value: CompositeViewValue): IO[Unit]

}

object ValidateCompositeView {

  def apply(
      aclCheck: AclCheck,
      projects: Projects,
      fetchPermissions: IO[Set[Permission]],
      client: ElasticSearchClient,
      deltaClient: DeltaClient,
      prefix: String,
      maxSources: Int,
      maxProjections: Int
  )(implicit baseUri: BaseUri): ValidateCompositeView = (uuid: UUID, value: CompositeViewValue) => {
    def validateAcls(cpSource: CrossProjectSource): IO[Unit] =
      aclCheck.authorizeForOr(cpSource.project, events.read, cpSource.identities)(CrossProjectSourceForbidden(cpSource))

    def validateProject(cpSource: CrossProjectSource) =
      projects.fetch(cpSource.project).mapError(_ => CrossProjectSourceProjectNotFound(cpSource)).void

    def validatePermission(permission: Permission) =
      fetchPermissions.flatMap { perms =>
        IO.raiseWhen(!perms.contains(permission))(PermissionIsNotDefined(permission))
      }

    def validateIndex(es: ElasticSearchProjection, index: IndexLabel) = toCatsIO {
      client
        .createIndex(index, Some(es.mapping), es.settings)
        .mapError {
          case err: HttpClientStatusError => InvalidElasticSearchProjectionPayload(err.jsonBody)
          case err                        => WrappedElasticSearchClientError(err)
        }
        .void
    }

    val checkRemoteEvent: RemoteProjectSource => IO[Unit] = deltaClient.checkElems

    val validateSource: CompositeViewSource => IO[Unit] = {
      case _: ProjectSource             => IO.unit
      case cpSource: CrossProjectSource => validateAcls(cpSource) >> validateProject(cpSource)
      case rs: RemoteProjectSource      =>
        checkRemoteEvent(rs).adaptError { case http: HttpClientError =>
          InvalidRemoteProjectSource(rs, http)
        }
    }

    val validateProjection: CompositeViewProjection => IO[Unit] = {
      case sparql: SparqlProjection    => validatePermission(sparql.permission)
      case es: ElasticSearchProjection =>
        validatePermission(es.permission) >>
          validateIndex(es, projectionIndex(es, uuid, prefix))
    }

    for {
      _          <- IO.raiseWhen(value.sources.length > maxSources)(TooManySources(value.sources.length, maxSources))
      _          <- IO.raiseWhen(value.projections.length > maxProjections)(
                      TooManyProjections(value.projections.length, maxProjections)
                    )
      allIds      = value.sources.keys.toList ++ value.projections.keys.toList
      distinctIds = allIds.distinct
      _          <- IO.raiseWhen(allIds.size != distinctIds.size)(DuplicateIds(allIds))
      _          <- value.sources.toNel.foldLeftM(()) { case (_, (_, s)) => validateSource(s) }
      _          <- value.projections.toNel.foldLeftM(()) { case (_, (_, p)) => validateProjection(p) }

    } yield ()

  }

}
