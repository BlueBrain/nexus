package ch.epfl.bluebrain.nexus.delta.sdk

import java.util.UUID

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{RevisionNotFound, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import fs2.Stream
import monix.bio.{IO, Task, UIO}

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
    * Fetch a project resource by its uuid and its organization uuid
    * @param orgUuid     the unique organization identifier
    * @param projectUuid the unique project identifier
    */
  def fetch(orgUuid: UUID, projectUuid: UUID): IO[ProjectNotFound, Option[ProjectResource]] =
    fetch(projectUuid).flatMap {
      case Some(res) if res.value.organizationUuid != orgUuid => IO.raiseError(ProjectNotFound(orgUuid, projectUuid))
      case other                                              => IO.pure(other)
    }

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
    * Fetch a project resource by its uuid and its organization uuid
    * @param orgUuid     the unique organization identifier
    * @param projectUuid the unique project identifier
    * @param rev         the revision to be retrieved
    */
  def fetchAt(orgUuid: UUID, projectUuid: UUID, rev: Long): IO[ProjectRejection, Option[ProjectResource]] =
    fetchAt(projectUuid, rev).flatMap {
      case Some(res) if res.value.organizationUuid != orgUuid => IO.raiseError(ProjectNotFound(orgUuid, projectUuid))
      case other                                              => IO.pure(other)
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

  /**
    * A non terminating stream of events for projects. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[ProjectEvent]]

  /**
    * The current project events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[ProjectEvent]]
}

object Projects {

  /**
    * The projects module type.
    */
  final val moduleType: String = "project"

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

  private[delta] def evaluate(
      fetchOrg: Label => UIO[Option[OrganizationResource]]
  )(state: ProjectState, command: ProjectCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      uuidF: UUIDF
  ): IO[ProjectRejection, ProjectEvent] = {

    def validateOrg(orgLabel: Label) =
      fetchOrg(orgLabel).flatMap {
        case Some(org) if org.deprecated => IO.raiseError(OrganizationIsDeprecated(orgLabel))
        case Some(org)                   => IO.pure(org)
        case None                        => IO.raiseError(OrganizationNotFound(orgLabel))
      }

    def create(c: CreateProject) =
      state match {
        case Initial =>
          for {
            org  <- validateOrg(c.ref.organization)
            uuid <- uuidF()
            now  <- instant
          } yield {
            ProjectCreated(
              c.ref.project,
              uuid,
              c.ref.organization,
              org.value.uuid,
              1L,
              c.description,
              c.apiMappings,
              c.base,
              c.vocab,
              now,
              c.subject
            )
          }
        case _       =>
          IO.raiseError(ProjectAlreadyExists(c.ref))
      }

    def update(c: UpdateProject) =
      state match {
        case Initial                      =>
          IO.raiseError(ProjectNotFound(c.ref))
        case s: Current if c.rev != s.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(ProjectIsDeprecated(c.ref))
        case s: Current                   =>
          // format: off
          validateOrg(c.ref.organization) >>
              instant.map(ProjectUpdated(s.label, s.uuid, s.organizationLabel, s.organizationUuid, s.rev + 1, c.description, c.apiMappings, c.base, c.vocab,_, c.subject))
          // format: on
      }

    def deprecate(c: DeprecateProject) =
      state match {
        case Initial                      =>
          IO.raiseError(ProjectNotFound(c.ref))
        case s: Current if c.rev != s.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   =>
          IO.raiseError(ProjectIsDeprecated(c.ref))
        case s: Current                   =>
          // format: off
          validateOrg(c.ref.organization) >>
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
