package ch.epfl.bluebrain.nexus.delta.sdk

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectDescription, ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import monix.bio.{IO, UIO}

trait Projects {

  /**
    * Creates a new project.
    *
    * @param ref         the project reference
    * @param description the project information
    * @param caller      a reference to the subject that initiated the action
    */
  def create(
      ref: ProjectRef,
      description: ProjectDescription
  )(implicit caller: Subject): IO[ProjectRejection, ProjectResource]

  /**
    * Update an existing project.
    *
    * @param ref         the project reference
    * @param rev         the current project revision
    * @param description the project information
    * @param caller      a reference to the subject that initiated the action
    */
  def update(
      ref: ProjectRef,
      rev: Long,
      description: ProjectDescription
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
      case Some(value) => fetchAt(value.value.projectRef, rev)
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
