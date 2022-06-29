package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{Mapper, Transactors}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.{entityType, FetchOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsImpl.{ProjectsLog, UUIDCache}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectCommand.{CreateProject, DeleteProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import monix.bio.{IO, UIO}

import java.util.UUID

final class ProjectsImpl private (
    log: ProjectsLog,
    cache: UUIDCache,
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

  override def fetchProject[R](
      ref: ProjectRef,
      options: Set[ProjectFetchOptions]
  )(implicit subject: Subject, rejectionMapper: Mapper[ProjectRejection, R]): IO[R, Project] =
    IO.raiseError(ProjectNotFound(ref)).mapError(rejectionMapper.to)

  override def fetchProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectNotFound, R]): IO[R, Project] =
    fetch(ref).bimap(rejectionMapper.to, _.value)

  override def fetch(uuid: UUID): IO[ProjectNotFound, ProjectResource] =
    fetchFromCache(uuid).flatMap(fetch)

  override def fetchAt(uuid: UUID, rev: Int): IO[ProjectRejection.NotFound, ProjectResource] =
    super.fetchAt(uuid, rev).span("fetchProjectAtByUuid")

  private def fetchFromCache(uuid: UUID): IO[ProjectNotFound, ProjectRef] =
    cache.get(uuid).flatMap {
      case None        =>
        for {
          projects <- log.currentStates(Predicate.root, p => p.uuid -> p.project).compile.toList.hideErrors
          _        <- cache.putAll(projects.toMap)
          cached   <- cache.getOr(uuid, ProjectNotFound(uuid))
        } yield cached
      case Some(label) => UIO.pure(label)
    }

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams,
      ordering: Ordering[ProjectResource]
  ): UIO[SearchResults.UnscoredSearchResults[ProjectResource]] =
    log
      .currentStates(params.organization.fold(Predicate.root)(Predicate.Org), _.toResource(defaultApiMappings))
      .evalFilter(params.matches)
      .compile
      .toList
      .hideErrors
      .map { resources =>
        SearchResults(
          resources.size.toLong,
          resources.sorted(ordering).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .span("listProjects")

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

  type UUIDCache = KeyValueStore[UUID, ProjectRef]

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
  ): UIO[Projects] =
    KeyValueStore.localLRU[UUID, ProjectRef](config.cache).map { cache =>
      new ProjectsImpl(
        ScopedEventLog(Projects.definition(fetchAndValidateOrg), config.eventLog, xas),
        cache,
        scopeInitializations,
        defaultApiMappings
      )
    }
}
