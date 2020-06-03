package ch.epfl.bluebrain.nexus.admin.index

import java.util.UUID

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.config.Permissions._
import ch.epfl.bluebrain.nexus.admin.index.Cache._
import ch.epfl.bluebrain.nexus.admin.projects.{Project, ProjectResource}
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.AccessControlLists

/**
  * The project cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  * @tparam F the effect type ''F[_]''
  */
class ProjectCache[F[_]: Monad](store: KeyValueStore[F, UUID, ProjectResource]) extends Cache[F, Project](store) {

  private implicit val ordering: Ordering[ProjectResource] = Ordering.by { proj: ProjectResource =>
    s"${proj.value.organizationLabel}/${proj.value.label}"
  }

  /**
    * Return the elements on the store within the ''pagination'' bounds which are accessible by the provided acls with the permission 'projects/read'.
    * The elements returned are filtered by the provided organization label.
    *
    * @param params the filter parameters
    * @param pagination the pagination
    */
  def list(
      params: SearchParams,
      pagination: FromPagination
  )(implicit acls: AccessControlLists, config: IamClientConfig): F[UnscoredQueryResults[ProjectResource]] =
    store.values.map { values =>
      val filtered = values.filter {
        case ResourceF(_, _, rev, deprecated, types, _, createdBy, _, updatedBy, project: Project) =>
          params.organizationLabel.forall(_.matches(project.organizationLabel)) &&
            params.projectLabel.forall(_.matches(project.label)) &&
            params.deprecated.forall(_ == deprecated) &&
            params.createdBy.forall(_ == createdBy.id) &&
            params.updatedBy.forall(_ == updatedBy.id) &&
            params.rev.forall(_ == rev) &&
            params.types.subsetOf(types) &&
            acls.exists(project.organizationLabel, project.label, projects.read)
      }
      val count  = filtered.size.toLong
      val result = filtered.toList.sorted.slice(pagination.from, pagination.from + pagination.size)
      UnscoredQueryResults(count, result.map(UnscoredQueryResult(_)))
    }

  /**
    * Attempts to fetch the project resource with the provided organization and project labels
    *
    * @param org  the organization label
    * @param proj the project label
    */
  def getBy(org: String, proj: String): F[Option[ProjectResource]] =
    store.findValue(r => r.value.organizationLabel == org && r.value.label == proj)

  /**
    * Attempts to fetch the resource with the provided ''projUuid'' and ''orgUuid''
    *
    * @param orgUuid  the organization id
    * @param projUuid the project id
    */
  def get(orgUuid: UUID, projUuid: UUID): F[Option[ProjectResource]] =
    get(projUuid).map(_.filter(_.value.organizationUuid == orgUuid))
}

object ProjectCache {

  /**
    * Creates a new project index.
    */
  def apply[F[_]: Effect: Timer](implicit as: ActorSystem, config: KeyValueStoreConfig): ProjectCache[F] = {
    val function: (Long, ProjectResource) => Long = { case (_, res) => res.rev }
    new ProjectCache(KeyValueStore.distributed("projects", function))
  }
}
