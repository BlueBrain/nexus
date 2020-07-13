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
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStore.Subscription
import ch.epfl.bluebrain.nexus.commons.cache.{KeyValueStore, KeyValueStoreConfig, OnKeyValueStoreChange}
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig

/**
  * The project cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  * @tparam F the effect type ''F[_]''
  */
class ProjectCache[F[_]](store: KeyValueStore[F, UUID, ProjectResource])(implicit F: Monad[F], http: HttpConfig)
    extends Cache[F, Project](store) {

  implicit private val ordering: Ordering[ProjectResource] = Ordering.by { proj: ProjectResource =>
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
  )(implicit acls: AccessControlLists): F[UnscoredQueryResults[ProjectResource]] =
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
      val count    = filtered.size.toLong
      val result   = filtered.toList.sorted.slice(pagination.from, pagination.from + pagination.size)
      UnscoredQueryResults(count, result.map(UnscoredQueryResult(_)))
    }

  /**
    * Fetches all the projects that belong to the provided organization label
    *
   * @param org the organization label to filter the projects
    */
  def listUnsafe(org: String): F[List[ProjectResource]] =
    listUnsafe().map(_.filter(_.value.organizationLabel == org))

  /**
    * Fetches all the projects that belong to the provided organization id
    *
   * @param orgUuid the organization id to filter the projects
    */
  def listUnsafe(orgUuid: UUID): F[List[ProjectResource]] =
    listUnsafe().map(_.filter(_.value.organizationUuid == orgUuid))

  /**
    * Fetches all projects
    */
  def listUnsafe(): F[List[ProjectResource]] =
    store.values.map(_.toList.sorted)

  /**
    * Attempts to fetch the project resource with the provided organization and project labels
    *
    * @param org  the organization label
    * @param proj the project label
    */
  def getBy(org: String, proj: String): F[Option[ProjectResource]] =
    getBy(ProjectIdentifier.ProjectLabel(org, proj))

  /**
    * Attempts to fetch the resource with the provided ''projUuid'' and ''orgUuid''
    *
    * @param orgUuid  the organization id
    * @param projUuid the project id
    */
  def get(orgUuid: UUID, projUuid: UUID): F[Option[ProjectResource]] =
    get(projUuid).map(_.filter(_.value.organizationUuid == orgUuid))

  /**
    * Attempts to fetch the project resource with the provided project identifier
    *
   * @param identifier the project identifier (UUID or label)
    */
  def getBy(identifier: ProjectIdentifier): F[Option[ProjectResource]] =
    identifier match {
      case ProjectIdentifier.ProjectLabel(org, proj) =>
        store.values.map(_.find(project => project.value.organizationLabel == org && project.value.label == proj))
      case ProjectIdentifier.ProjectRef(id)          =>
        store.values.map(_.find(_.uuid == id))
    }

  /**
    * Subscribe to project cache events and call certain functions
    *
   * @param onAdded      function to be called when an event has been added to the cache
    * @param onUpdated    function to be called when an event has been updated from the cache
    * @param onDeprecated function to be called when an event has been deleted from the cache
    */
  def subscribe(
      onAdded: ProjectResource => F[Unit] = _ => F.unit,
      onUpdated: ProjectResource => F[Unit] = _ => F.unit,
      onDeprecated: ProjectResource => F[Unit] = _ => F.unit
  ): F[Subscription] =
    store.subscribe {
      OnKeyValueStoreChange(
        onCreate = (_, project) => onAdded(project),
        onUpdate = (_, project) => if (project.deprecated) onDeprecated(project) else onUpdated(project),
        onRemove = (_, _) => F.unit
      )
    }

}

object ProjectCache {

  /**
    * Creates a new project index.
    */
  def apply[F[_]: Effect: Timer](implicit
      as: ActorSystem,
      config: KeyValueStoreConfig,
      http: HttpConfig
  ): ProjectCache[F] = {
    val function: (Long, ProjectResource) => Long = { case (_, res) => res.rev }
    new ProjectCache(KeyValueStore.distributed("projects", function))
  }
}
