package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.{entityType, FetchOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsImpl.{logger, ProjectsLog}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.Stream

final class ProjectsImpl private (
    log: ProjectsLog,
    scopeInitializations: Set[ScopeInitialization],
    errorStore: ScopeInitializationErrorStore,
    override val defaultApiMappings: ApiMappings
) extends Projects {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      ref: ProjectRef,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectResource] =
    for {
      resource <- eval(CreateProject(ref, fields, caller)).span("createProject")
      _        <- scopeInitializations
                    .parUnorderedTraverse { init =>
                      init
                        .onProjectCreation(resource.value, caller)
                        .recoverWith { case e: ScopeInitializationFailed =>
                          errorStore.save(init.entityType, resource.value.ref, e)
                        }
                    }
                    .adaptError { case e: ScopeInitializationFailed => ProjectInitializationFailed(e) }
                    .span("initializeProject")
    } yield resource

  override def update(ref: ProjectRef, rev: Int, fields: ProjectFields)(implicit
      caller: Subject
  ): IO[ProjectResource] =
    eval(UpdateProject(ref, fields, rev, caller)).span("updateProject")

  override def deprecate(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectResource] =
    eval(DeprecateProject(ref, rev, caller)).span("deprecateProject") <*
      logger.info(s"Project '$ref' has been deprecated.")

  override def undeprecate(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectResource] = {
    eval(UndeprecateProject(ref, rev, caller)).span("undeprecateProject") <*
      logger.info(s"Project '$ref' has been undeprecated.")
  }

  override def delete(ref: ProjectRef, rev: Int)(implicit caller: Subject): IO[ProjectResource] =
    eval(DeleteProject(ref, rev, caller)).span("deleteProject") <*
      logger.info(s"Project '$ref' has been marked as deleted.")

  override def fetch(ref: ProjectRef): IO[ProjectResource] =
    log
      .stateOr(ref, ref, ProjectNotFound(ref))
      .map(_.toResource(defaultApiMappings))
      .span("fetchProject")

  override def fetchAt(ref: ProjectRef, rev: Int): IO[ProjectResource] =
    log
      .stateOr(ref, ref, rev, ProjectNotFound(ref), RevisionNotFound)
      .map(_.toResource(defaultApiMappings))
      .span("fetchProjectAt")

  override def fetchProject(ref: ProjectRef): IO[Project] = fetch(ref).map(_.value)

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams,
      ordering: Ordering[ProjectResource]
  ): IO[SearchResults.UnscoredSearchResults[ProjectResource]] =
    SearchResults(
      log
        .currentStates(params.organization.fold(Scope.root)(Scope.Org), _.toResource(defaultApiMappings))
        .evalFilter(params.matches),
      pagination,
      ordering
    ).span("listProjects")

  override def currentRefs: Stream[IO, ProjectRef] =
    log.currentStates(Scope.root).map(_.value.project)

  override def states(offset: Offset): ElemStream[ProjectState] =
    log.states(Scope.root, offset).map {
      _.toElem { p => Some(p.project) }
    }

  private def eval(cmd: ProjectCommand): IO[ProjectResource] =
    log.evaluate(cmd.ref, cmd.ref, cmd).map(_._2.toResource(defaultApiMappings))

}

object ProjectsImpl {

  type ProjectsLog =
    ScopedEventLog[ProjectRef, ProjectState, ProjectCommand, ProjectEvent, ProjectRejection]

  private val logger = Logger[ProjectsImpl]

  /**
    * Constructs a [[Projects]] instance.
    */
  final def apply(
      fetchAndValidateOrg: FetchOrganization,
      validateDeletion: ValidateProjectDeletion,
      scopeInitializations: Set[ScopeInitialization],
      defaultApiMappings: ApiMappings,
      config: ProjectsConfig,
      xas: Transactors,
      clock: Clock[IO]
  )(implicit
      base: BaseUri,
      uuidF: UUIDF
  ): Projects =
    new ProjectsImpl(
      ScopedEventLog(Projects.definition(fetchAndValidateOrg, validateDeletion, clock), config.eventLog, xas),
      scopeInitializations,
      ScopeInitializationErrorStore(xas, clock),
      defaultApiMappings
    )
}
