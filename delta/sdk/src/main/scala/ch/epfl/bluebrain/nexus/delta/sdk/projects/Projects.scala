package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{Organization, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectCommand.{CreateProject, DeleteProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectMarkedForDeletion, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{IncorrectRev, ProjectAlreadyExists, ProjectIsDeprecated, ProjectIsMarkedForDeletion, ProjectNotFound, WrappedOrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEntityDefinition, StateMachine}
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
  def currentRefs: Stream[IO, ProjectRef]

  /**
    * Stream project states in a non-finite stream
    */
  def states(offset: Offset): ElemStream[ProjectState]

  /**
    * The default api mappings
    */
  def defaultApiMappings: ApiMappings

}

object Projects {

  type FetchOrganization = Label => IO[Organization]

  /**
    * The projects entity type.
    */
  final val entityType: EntityType = EntityType("project")

  /**
    * Encode the project reference as an [[Iri]]
    */
  def encodeId(project: ProjectRef): Iri = ResourceUris.project(project).relativeAccessUri.toIri

  private[delta] def next(state: Option[ProjectState], event: ProjectEvent): Option[ProjectState] =
    (state, event) match {
      // format: off
      case (None, ProjectCreated(label, uuid, orgLabel, orgUuid, _, desc, am, base, vocab, instant, subject))  =>
        Some(ProjectState(label, uuid, orgLabel, orgUuid, 1, deprecated = false, markedForDeletion = false, desc, am, ProjectBase.unsafe(base.value), vocab.value, instant, subject, instant, subject))

      case (Some(s), ProjectUpdated(_, _, _, _, rev, desc, am, base, vocab, instant, subject))                 =>
        Some(s.copy(description = desc, apiMappings = am, base = ProjectBase.unsafe(base.value), vocab = vocab.value, rev = rev, updatedAt = instant, updatedBy = subject))

      case (Some(s), ProjectDeprecated(_, _, _, _, rev, instant, subject))                                     =>
        Some(s.copy(rev = rev, deprecated = true, updatedAt = instant, updatedBy = subject))

      case (Some(s), ProjectMarkedForDeletion(_, _, _, _, rev, instant, subject))                              =>
        Some(s.copy(rev = rev, markedForDeletion = true, updatedAt = instant, updatedBy = subject))

      case (_, _)                                                                                                => None
      // format: on
    }

  private[delta] def evaluate(
      orgs: Organizations,
      validateDeletion: ValidateProjectDeletion
  )(state: Option[ProjectState], command: ProjectCommand)(implicit
      clock: Clock[IO],
      uuidF: UUIDF
  ): IO[ProjectEvent] = {
    val f: FetchOrganization = label =>
      orgs
        .fetchActiveOrganization(label)
        .adaptError { case o: OrganizationRejection => WrappedOrganizationRejection(o) }
    evaluate(f, validateDeletion)(state, command)
  }

  private[sdk] def evaluate(
      fetchAndValidateOrg: FetchOrganization,
      validateDeletion: ValidateProjectDeletion
  )(state: Option[ProjectState], command: ProjectCommand)(implicit
      clock: Clock[IO],
      uuidF: UUIDF
  ): IO[ProjectEvent] = {

    def create(c: CreateProject): IO[ProjectCreated] = state match {
      case None =>
        for {
          org  <- fetchAndValidateOrg(c.ref.organization)
          uuid <- uuidF()
          now  <- clock.realTimeInstant
        } yield ProjectCreated(
          c.ref.project,
          uuid,
          c.ref.organization,
          org.uuid,
          1,
          c.description,
          c.apiMappings,
          c.base,
          c.vocab,
          now,
          c.subject
        )
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
          // format: off
          fetchAndValidateOrg(c.ref.organization) >>
              clock.realTimeInstant.map(ProjectUpdated(s.label, s.uuid, s.organizationLabel, s.organizationUuid, s.rev + 1, c.description, c.apiMappings, c.base, c.vocab,_, c.subject))
          // format: on
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
          fetchAndValidateOrg(c.ref.organization) >>
              clock.realTimeInstant.map(ProjectDeprecated(s.label, s.uuid,s.organizationLabel, s.organizationUuid,s.rev + 1, _, c.subject))
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
      case c: CreateProject    => create(c)
      case c: UpdateProject    => update(c)
      case c: DeprecateProject => deprecate(c)
      case c: DeleteProject    => delete(c)
    }
  }

  /**
    * Entity definition for [[Projects]]
    */
  def definition(fetchAndValidateOrg: FetchOrganization, validateDeletion: ValidateProjectDeletion)(implicit
      clock: Clock[IO],
      uuidF: UUIDF
  ): ScopedEntityDefinition[ProjectRef, ProjectState, ProjectCommand, ProjectEvent, ProjectRejection] =
    ScopedEntityDefinition.untagged(
      entityType,
      StateMachine(None, evaluate(fetchAndValidateOrg, validateDeletion)(_, _), next),
      ProjectEvent.serializer,
      ProjectState.serializer,
      onUniqueViolation = (id: ProjectRef, c: ProjectCommand) =>
        c match {
          case _: CreateProject => ProjectAlreadyExists(id)
          case c                => IncorrectRev(c.rev, c.rev + 1)
        }
    )
}
