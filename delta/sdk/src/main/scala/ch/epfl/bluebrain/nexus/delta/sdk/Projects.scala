package ch.epfl.bluebrain.nexus.delta.sdk

import java.util.UUID

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant
import monix.bio.{IO, UIO}

trait Projects {

  /**
    * Creates a new project.
    *
    * @param ref    the project reference
    * @param fields the project information
    * @param caller a reference to the subject that initiated the action
    */
  def create(
      ref: ProjectRef,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectRejection, ProjectResource]

  /**
    * Update an existing project.
    *
    * @param ref    the project reference
    * @param rev    the current project revision
    * @param fields the project information
    * @param caller a reference to the subject that initiated the action
    */
  def update(
      ref: ProjectRef,
      rev: Long,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectRejection, ProjectResource]

  /**
    * Deprecate an existing project.
    *
    * @param ref    the project reference
    * @param rev    the current project revision
    * @param caller a reference to the subject that initiated the action
    */
  def deprecate(
      ref: ProjectRef,
      rev: Long
  )(implicit caller: Subject): IO[ProjectRejection, ProjectResource]

  /**
    * Fetches a project resource based on its reference.
    *
    * @param ref the project reference
    */
  def fetch(ref: ProjectRef): UIO[Option[ProjectResource]]

  /**
    * Fetches a project resource at a specific revision based on its reference.
    *
    * @param ref the project reference
    * @param rev the revision to be retrieved
    */
  def fetchAt(ref: ProjectRef, rev: Long): IO[RevisionNotFound, Option[ProjectResource]]

  /**
    * Fetches a project resource based on its uuid.
    *
    * @param uuid the unique project identifier
    */
  def fetch(uuid: UUID): UIO[Option[ProjectResource]]

  /**
    * Fetches a project resource at a specific revision based on its uuid.
    *
    * @param uuid the unique project identifier
    * @param rev  the revision to be retrieved
    */
  def fetchAt(uuid: UUID, rev: Long): IO[RevisionNotFound, Option[ProjectResource]] =
    fetch(uuid).flatMap {
      case Some(value) => fetchAt(value.value.ref, rev)
      case None        => IO.pure(None)
    }

  /**
    * Lists all projects.
    *
    * @param pagination the pagination settings
    * @param params     filter parameters for the listing
    * @return a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: ProjectSearchParams = ProjectSearchParams.none
  ): UIO[UnscoredSearchResults[ProjectResource]]
}

object Projects {

  private[delta] def next(state: ProjectState, event: ProjectEvent): ProjectState =
    (state, event) match {
      // format: off
      case (Initial, ProjectCreated(label, uuid, orgLabel, orgUuid, _, desc, am, base, vocab, instant, subject))  =>
        Current(label, uuid, orgLabel, orgUuid, 1L, deprecated = false, desc, am, base.value, vocab.value, instant, subject, instant, subject)

      case (c: Current, ProjectUpdated(_, _, _, _, rev, desc, am, base, vocab, instant, subject))                 =>
        c.copy(description = desc, apiMappings = am, base = base.value, vocab = vocab.value, rev = rev, updatedAt = instant, updatedBy = subject)

      case (c: Current, ProjectDeprecated(_, _, _, _, rev, instant, subject))                                     =>
        c.copy(rev = rev, deprecated = true, updatedAt = instant, updatedBy = subject)

      case (s, _)                                                                                                => s
      // format: on
    }

  private[delta] def evaluate(orgs: Organizations)(state: ProjectState, command: ProjectCommand)(implicit
      clock: Clock[UIO] = IO.clock
  ): IO[ProjectRejection, ProjectEvent] = {

    def checkNotExistOrDeprecated(orgLabel: Label) =
      orgs.fetch(orgLabel).flatMap {
        case Some(org) if org.deprecated => IO.raiseError(OrganizationIsDeprecated(orgLabel))
        case Some(_)                     => IO.unit
        case None                        => IO.raiseError(OrganizationNotFound(orgLabel))
      }

    def create(c: CreateProject) =
      state match {
        case Initial =>
          // format: off
          checkNotExistOrDeprecated(c.organizationLabel) >>
              instant.map(ProjectCreated(c.label, c.uuid, c.organizationLabel, c.organizationUuid, 1L, c.description, c.apiMappings, c.base, c.vocab,_, c.subject))
          // format: on
        case _       =>
          IO.raiseError(ProjectAlreadyExists(ProjectRef(c.organizationLabel, c.label)))
      }

    def update(c: UpdateProject) =
      state match {
        case Initial                      =>
          IO.raiseError(ProjectNotFound(ProjectRef(c.organizationLabel, c.label)))
        case s: Current if c.rev != s.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(ProjectIsDeprecated(ProjectRef(c.organizationLabel, c.label)))
        case s: Current                   =>
          // format: off
          checkNotExistOrDeprecated(c.organizationLabel) >>
              instant.map(ProjectUpdated(s.label, s.uuid, s.organizationLabel, s.organizationUuid, s.rev + 1, c.description, c.apiMappings, c.base, c.vocab,_, c.subject))
          // format: on
      }

    def deprecate(c: DeprecateProject) =
      state match {
        case Initial                      =>
          IO.raiseError(ProjectNotFound(ProjectRef(c.organizationLabel, c.label)))
        case s: Current if c.rev != s.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(ProjectIsDeprecated(ProjectRef(c.organizationLabel, c.label)))
        case s: Current                   =>
          // format: off
          checkNotExistOrDeprecated(c.organizationLabel) >>
              instant.map(ProjectDeprecated(s.label, s.uuid,s.organizationLabel, s.organizationUuid,s.rev + 1, _, c.subject))
          // format: on
      }

    command match {
      case c: CreateProject    => create(c)
      case c: UpdateProject    => update(c)
      case c: DeprecateProject => deprecate(c)
    }
  }
}
