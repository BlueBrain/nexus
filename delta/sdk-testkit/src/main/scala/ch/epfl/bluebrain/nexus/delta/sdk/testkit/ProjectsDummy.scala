package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.UUID

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand.{CreateProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectsDummy.{ProjectsCache, ProjectsJournal}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Projects implementation
  * @param journal       the journal to store events
  * @param cache         the cache to store resources
  * @param semaphore     a semaphore for serializing write operations on the journal
  * @param organizations an Organizations instance
  */
final class ProjectsDummy private (
    journal: ProjectsJournal,
    cache: ProjectsCache,
    semaphore: IOSemaphore,
    organizations: Organizations,
    applyOwnerPermissions: ApplyOwnerPermissionsDummy
)(implicit base: BaseUri, clock: Clock[UIO], uuidf: UUIDF)
    extends Projects {

  override def create(ref: ProjectRef, fields: ProjectFields)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(
      CreateProject(
        ref,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(ref),
        fields.vocabOrGenerated(ref),
        caller
      )
    ) <* applyOwnerPermissions.onProject(ref, caller).leftMap(OwnerPermissionsFailed(ref, _))

  override def update(ref: ProjectRef, rev: Long, fields: ProjectFields)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(
      UpdateProject(
        ref,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(ref),
        fields.vocabOrGenerated(ref),
        rev,
        caller
      )
    )

  override def deprecate(ref: ProjectRef, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(DeprecateProject(ref, rev, caller))

  override def fetch(ref: ProjectRef): IO[ProjectNotFound, ProjectResource] =
    cache.fetchOr(ref, ProjectNotFound(ref))

  override def fetchAt(ref: ProjectRef, rev: Long): IO[ProjectRejection.NotFound, ProjectResource] =
    journal
      .stateAt(ref, rev, Initial, Projects.next, ProjectRejection.RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))
      .flatMap(IO.fromOption(_, ProjectNotFound(ref)))

  override def fetchActiveProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectRejection, R]): IO[R, Project] =
    (organizations.fetchActiveOrganization(ref.organization) >>
      fetch(ref).flatMap {
        case resource if resource.deprecated => IO.raiseError(ProjectIsDeprecated(ref))
        case resource                        => IO.pure(resource.value)
      }).leftMap(rejectionMapper.to)

  override def fetchProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectRejection, R]): IO[R, Project] =
    cache.fetchOr(ref, ProjectNotFound(ref)).bimap(rejectionMapper.to, _.value)

  override def fetch(uuid: UUID): IO[ProjectNotFound, ProjectResource] =
    cache.fetchByOr(p => p.uuid == uuid, ProjectNotFound(uuid))

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams,
      ordering: Ordering[ProjectResource]
  ): UIO[SearchResults.UnscoredSearchResults[ProjectResource]] =
    cache.list(pagination, params, ordering)

  private def eval(cmd: ProjectCommand): IO[ProjectRejection, ProjectResource] =
    semaphore.withPermit {
      for {
        state <- journal.currentState(cmd.ref, Initial, Projects.next).map(_.getOrElse(Initial))
        event <- Projects.evaluate(organizations)(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Projects.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.ref)))
        _     <- cache.setToCache(res)
      } yield res
    }

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ProjectEvent]] =
    journal.events(offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[ProjectEvent]] =
    journal.currentEvents(offset)
}

object ProjectsDummy {

  type ProjectsJournal = Journal[ProjectRef, ProjectEvent]
  type ProjectsCache   = ResourceCache[ProjectRef, Project]

  implicit private val idLens: Lens[ProjectEvent, ProjectRef] = (event: ProjectEvent) =>
    ProjectRef(event.organizationLabel, event.label)

  implicit private val lens: Lens[Project, ProjectRef] = _.ref

  /**
    * Creates a project dummy instance
    *
    * @param organizations         an Organizations instance
    * @param applyOwnerPermissions to apply owner permissions on project creation
    */
  def apply(
      organizations: Organizations,
      applyOwnerPermissions: ApplyOwnerPermissionsDummy
  )(implicit base: BaseUri, clock: Clock[UIO], uuidf: UUIDF): UIO[ProjectsDummy] =
    for {
      journal <- Journal(moduleType)
      cache   <- ResourceCache[ProjectRef, Project]
      sem     <- IOSemaphore(1L)
    } yield new ProjectsDummy(journal, cache, sem, organizations, applyOwnerPermissions)

  /**
    * Creates a project dummy instance where ownerPermissions don't matter
    * @param organizations an Organizations instance
    */
  def apply(organizations: Organizations)(implicit base: BaseUri, clock: Clock[UIO], uuidf: UUIDF): UIO[ProjectsDummy] =
    AclsDummy(PermissionsDummy(Set.empty)).flatMap { acls =>
      apply(organizations, ApplyOwnerPermissionsDummy(acls, Set.empty, Identity.Anonymous))
    }

}
