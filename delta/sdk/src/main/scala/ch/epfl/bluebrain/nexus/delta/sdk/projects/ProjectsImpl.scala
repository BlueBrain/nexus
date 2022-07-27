package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.{entityType, FetchOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsImpl.ProjectsLog
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectCommand.{CreateProject, DeleteProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import monix.bio.{IO, UIO}

final class ProjectsImpl private (
    log: ProjectsLog,
    scopeInitializations: Set[ScopeInitialization],
    override val defaultApiMappings: ApiMappings
)(implicit base: BaseUri)
    extends Projects {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      ref: ProjectRef,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectRejection, ProjectResource] =
    for {
      resource <- eval(
                    CreateProject(
                      ref,
                      fields.description,
                      fields.apiMappings,
                      fields.baseOrGenerated(ref),
                      fields.vocabOrGenerated(ref),
                      caller
                    )
                  ).span("createProject")
      _        <- IO.parTraverseUnordered(scopeInitializations)(_.onProjectCreation(resource.value, caller))
                    .void
                    .mapError(ProjectInitializationFailed)
                    .span("initializeProject")
    } yield resource

  override def update(ref: ProjectRef, rev: Int, fields: ProjectFields)(implicit
      caller: Subject
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
    ).span("updateProject")

  override def deprecate(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectRejection, ProjectResource] =
    eval(DeprecateProject(ref, rev, caller)).span("deprecateProject")

  override def delete(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectRejection, ProjectResource] =
    eval(DeleteProject(ref, rev, caller)).span("deleteProject")

  override def fetch(ref: ProjectRef): IO[ProjectNotFound, ProjectResource] =
    log
      .stateOr(ref, ref, ProjectNotFound(ref))
      .map(_.toResource(defaultApiMappings))
      .span("fetchProject")

  override def fetchAt(ref: ProjectRef, rev: Int): IO[ProjectRejection.NotFound, ProjectResource] =
    log
      .stateOr(ref, ref, rev, ProjectNotFound(ref), RevisionNotFound)
      .map(_.toResource(defaultApiMappings))
      .span("fetchProjectAt")

  override def fetchProject(ref: ProjectRef): IO[ProjectNotFound, Project] = fetch(ref).map(_.value)

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams,
      ordering: Ordering[ProjectResource]
  ): UIO[SearchResults.UnscoredSearchResults[ProjectResource]] =
    SearchResults(
      log
        .currentStates(params.organization.fold(Predicate.root)(Predicate.Org), _.toResource(defaultApiMappings))
        .evalFilter(params.matches),
      pagination,
      ordering
    ).span("listProjects")

  override def currentEvents(offset: Offset): EnvelopeStream[ProjectRef, ProjectEvent] =
    log.currentEvents(Predicate.root, offset)

  override def events(offset: Offset): EnvelopeStream[ProjectRef, ProjectEvent] =
    log.events(Predicate.root, offset)

  private def eval(cmd: ProjectCommand): IO[ProjectRejection, ProjectResource] =
    log.evaluate(cmd.ref, cmd.ref, cmd).map(_._2.toResource(defaultApiMappings))

}

object ProjectsImpl {

  type ProjectsLog =
    ScopedEventLog[ProjectRef, ProjectState, ProjectCommand, ProjectEvent, ProjectRejection]

  /**
    * Constructs a [[Projects]] instance.
    */
  final def apply(
      fetchAndValidateOrg: FetchOrganization,
      scopeInitializations: Set[ScopeInitialization],
      defaultApiMappings: ApiMappings,
      config: ProjectsConfig,
      xas: Transactors
  )(implicit
      base: BaseUri,
      clock: Clock[UIO],
      uuidF: UUIDF
  ): Projects =
    new ProjectsImpl(
      ScopedEventLog(Projects.definition(fetchAndValidateOrg), config.eventLog, xas),
      scopeInitializations,
      defaultApiMappings
    )
}
