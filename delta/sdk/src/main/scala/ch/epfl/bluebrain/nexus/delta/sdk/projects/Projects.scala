package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceAccess}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectCommand.*
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectEvent.*
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{IncorrectRev, ProjectAlreadyExists, ProjectIsDeprecated, ProjectIsMarkedForDeletion, ProjectIsNotDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SuccessElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, ScopedEntityDefinition, StateMachine}
import fs2.Stream

trait Projects {

  /**
    * Creates a new project.
    *
    * @param ref
    *   the project reference
    * @param fields
    *   the project information
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def create(
      ref: ProjectRef,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectResource]

  /**
    * Update an existing project.
    *
    * @param ref
    *   the project reference
    * @param rev
    *   the current project revision
    * @param fields
    *   the project information
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def update(
      ref: ProjectRef,
      rev: Int,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectResource]

  /**
    * Deprecate an existing project.
    *
    * @param ref
    *   the project reference
    * @param rev
    *   the current project revision
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def deprecate(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectResource]

  /**
    * Un-deprecate an existing project.
    *
    * @param ref
    *   the project reference
    * @param rev
    *   the current project revision
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def undeprecate(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectResource]

  /**
    * Deletes an existing project.
    *
    * @param ref
    *   the project reference
    * @param rev
    *   the current project revision
    * @param caller
    *   a reference to the subject that initiated the action
    */
  def delete(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectResource]

  /**
    * Fetches a project resource based on its reference.
    *
    * @param ref
    *   the project reference
    */
  def fetch(ref: ProjectRef): IO[ProjectResource]

  /**
    * Fetches the current project, rejecting if the project does not exists.
    *
    * @param ref
    *   the project reference
    */
  def fetchProject(ref: ProjectRef): IO[Project]

  /**
    * Fetches a project resource at a specific revision based on its reference.
    *
    * @param ref
    *   the project reference
    * @param rev
    *   the revision to be retrieved
    */
  def fetchAt(ref: ProjectRef, rev: Int): IO[ProjectResource]

  /**
    * Lists all projects.
    *
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters for the listing
    * @param ordering
    *   the response ordering
    * @return
    *   a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: ProjectSearchParams,
      ordering: Ordering[ProjectResource]
  ): IO[UnscoredSearchResults[ProjectResource]]

  /**
    * Stream all references of existing projects in a finite stream
    */
  def currentRefs(scope: Scope): Stream[IO, ProjectRef]

  /**
    * Stream project states in a non-finite stream
    */
  def states(offset: Offset): SuccessElemStream[ProjectState]

  /**
    * The default api mappings
    */
  def defaultApiMappings: ApiMappings

}

object Projects {

  /**
    * The projects entity type.
    */
  final val entityType: EntityType = EntityType("project")

  /**
    * Encode the project reference as an [[Iri]]
    */
  def encodeId(project: ProjectRef): Iri = ResourceAccess.project(project).relativeUri.toIri

  private[delta] def next(state: Option[ProjectState], event: ProjectEvent): Option[ProjectState] =
    (state, event) match {
      // format: off
      case (None, ProjectCreated(label, uuid, orgLabel, orgUuid, _, desc, am, base, vocab, enforceSchema, instant, subject))  =>
        Some(ProjectState(label, uuid, orgLabel, orgUuid, 1, deprecated = false, markedForDeletion = false, desc, am, ProjectBase(base.value), vocab.value, enforceSchema, instant, subject, instant, subject))

      case (Some(s), ProjectUpdated(_, _, _, _, rev, desc, am, base, vocab, enforceSchema, instant, subject))                 =>
        Some(s.copy(description = desc, apiMappings = am, base = ProjectBase(base.value), vocab = vocab.value, enforceSchema = enforceSchema, rev = rev, updatedAt = instant, updatedBy = subject))

      case (Some(s), ProjectDeprecated(_, _, _, _, rev, instant, subject))                                     =>
        Some(s.copy(rev = rev, deprecated = true, updatedAt = instant, updatedBy = subject))

      case (Some(s), ProjectUndeprecated(_, _, _, _, rev, instant, subject)) =>
        Some(s.copy(rev = rev, deprecated = false, updatedAt = instant, updatedBy = subject))

      case (Some(s), ProjectMarkedForDeletion(_, _, _, _, rev, instant, subject))                              =>
        Some(s.copy(rev = rev, markedForDeletion = true, updatedAt = instant, updatedBy = subject))

      case (_, _)                                                                                                => None
      // format: on
    }

  private[sdk] def evaluate(
      fetchActiveOrg: FetchActiveOrganization,
      onCreate: ProjectRef => IO[Unit],
      validateDeletion: ValidateProjectDeletion,
      clock: Clock[IO]
  )(state: Option[ProjectState], command: ProjectCommand)(implicit base: BaseUri, uuidF: UUIDF): IO[ProjectEvent] = {

    def create(c: CreateProject): IO[ProjectCreated] = state match {
      case None =>
        for {
          org  <- fetchActiveOrg(c.ref.organization)
          uuid <- uuidF()
          now  <- clock.realTimeInstant
          _    <- onCreate(c.ref)
        } yield ProjectCreated(c.ref, uuid, org.uuid, c.fields, now, c.subject)
      case _    => IO.raiseError(ProjectAlreadyExists(c.ref))
    }

    def update(c: UpdateProject): IO[ProjectUpdated] =
      state match {
        case None                           =>
          IO.raiseError(ProjectNotFound(c.ref))
        case Some(s) if c.rev != s.rev      =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated        =>
          IO.raiseError(ProjectIsDeprecated(c.ref))
        case Some(s) if s.markedForDeletion =>
          IO.raiseError(ProjectIsMarkedForDeletion(c.ref))
        case Some(s)                        =>
          fetchActiveOrg(c.ref.organization) >>
            clock.realTimeInstant.map(
              ProjectUpdated(c.ref, s.uuid, s.organizationUuid, s.rev + 1, c.fields, _, c.subject)
            )
      }

    def deprecate(c: DeprecateProject) =
      state match {
        case None                           =>
          IO.raiseError(ProjectNotFound(c.ref))
        case Some(s) if c.rev != s.rev      =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.deprecated        =>
          IO.raiseError(ProjectIsDeprecated(c.ref))
        case Some(s) if s.markedForDeletion =>
          IO.raiseError(ProjectIsMarkedForDeletion(c.ref))
        case Some(s)                        =>
          // format: off
          fetchActiveOrg(c.ref.organization) >>
              clock.realTimeInstant.map(ProjectDeprecated(s.label, s.uuid,s.organizationLabel, s.organizationUuid,s.rev + 1, _, c.subject))
          // format: on
      }

    def undeprecate(c: UndeprecateProject) =
      state match {
        case None                           =>
          IO.raiseError(ProjectNotFound(c.ref))
        case Some(s) if c.rev != s.rev      =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if !s.deprecated       =>
          IO.raiseError(ProjectIsNotDeprecated(c.ref))
        case Some(s) if s.markedForDeletion =>
          IO.raiseError(ProjectIsMarkedForDeletion(c.ref))
        case Some(s)                        =>
          // format: off
          fetchActiveOrg(c.ref.organization) >>
            clock.realTimeInstant.map(ProjectUndeprecated(s.label, s.uuid, s.organizationLabel, s.organizationUuid, s.rev + 1, _, c.subject))
        // format: on
      }

    def delete(c: DeleteProject) =
      state match {
        case None                           =>
          IO.raiseError(ProjectNotFound(c.ref))
        case Some(s) if c.rev != s.rev      =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if s.markedForDeletion =>
          IO.raiseError(ProjectIsMarkedForDeletion(c.ref))
        case Some(s)                        =>
          // format: off
          validateDeletion(c.ref) >>
            clock.realTimeInstant.map(ProjectMarkedForDeletion(s.label, s.uuid,s.organizationLabel, s.organizationUuid,s.rev + 1, _, c.subject))
        // format: on
      }

    command match {
      case c: CreateProject      => create(c)
      case c: UpdateProject      => update(c)
      case c: DeprecateProject   => deprecate(c)
      case c: UndeprecateProject => undeprecate(c)
      case c: DeleteProject      => delete(c)
    }
  }

  /**
    * Entity definition for [[Projects]]
    */
  def definition(
      fetchActiveOrg: FetchActiveOrganization,
      onCreate: ProjectRef => IO[Unit],
      validateDeletion: ValidateProjectDeletion,
      clock: Clock[IO]
  )(implicit
      base: BaseUri,
      uuidF: UUIDF
  ): ScopedEntityDefinition[ProjectRef, ProjectState, ProjectCommand, ProjectEvent, ProjectRejection] = {
    ScopedEntityDefinition.untagged(
      entityType,
      StateMachine(None, evaluate(fetchActiveOrg, onCreate, validateDeletion, clock)(_, _), next),
      ProjectEvent.serializer,
      ProjectState.serializer,
      _ => None,
      onUniqueViolation = (id: ProjectRef, c: ProjectCommand) =>
        c match {
          case _: CreateProject => ProjectAlreadyExists(id)
          case c                => IncorrectRev(c.rev, c.rev + 1)
        }
    )
  }
}
