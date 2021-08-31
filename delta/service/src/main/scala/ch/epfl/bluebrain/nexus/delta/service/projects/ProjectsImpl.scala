package ch.epfl.bluebrain.nexus.delta.service.projects

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{Mapper, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.{moduleType, uuidFrom}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.Deleting
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand.{CreateProject, DeleteProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions.allQuotas
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, ResourcesDeletionStatus}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{DeletionStatusCache, ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import java.util.UUID

final class ProjectsImpl private (
    agg: ProjectsAggregate,
    eventLog: EventLog[Envelope[ProjectEvent]],
    index: ProjectsCache,
    deletionCache: DeletionStatusCache,
    organizations: Organizations,
    quotas: Quotas,
    scopeInitializations: Set[ScopeInitialization],
    defaultApiMappings: ApiMappings
)(implicit base: BaseUri)
    extends Projects {

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
                  ).named("createProject", moduleType)
      _        <- IO.parTraverseUnordered(scopeInitializations)(_.onProjectCreation(resource.value, caller))
                    .void
                    .mapError(ProjectInitializationFailed)
                    .named("initializeProject", moduleType)
    } yield resource

  override def update(ref: ProjectRef, rev: Long, fields: ProjectFields)(implicit
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
    ).named("updateProject", moduleType)

  override def deprecate(ref: ProjectRef, rev: Long)(implicit caller: Subject): IO[ProjectRejection, ProjectResource] =
    eval(DeprecateProject(ref, rev, caller)).named("deprecateProject", moduleType)

  override def delete(ref: ProjectRef, rev: Long)(implicit
      caller: Subject,
      referenceFinder: ProjectReferenceFinder
  ): IO[ProjectRejection, (UUID, ProjectResource)] = {
    for {
      references <- referenceFinder(ref)
      _          <- IO.raiseUnless(references.value.isEmpty)(ProjectIsReferenced(ref, references))
      resource   <- eval(DeleteProject(ref, rev, caller))
      uuid        = uuidFrom(ref, resource.updatedAt)
      _          <- deletionCache.put(
                      uuid,
                      ResourcesDeletionStatus(
                        progress = Deleting,
                        project = ref,
                        projectCreatedBy = resource.createdBy,
                        projectCreatedAt = resource.createdAt,
                        createdBy = caller,
                        createdAt = resource.updatedAt,
                        updatedAt = resource.updatedAt,
                        uuid = uuid
                      )
                    )
    } yield uuid -> resource
  }.named("deleteProject", moduleType)

  override def fetchDeletionStatus: UIO[UnscoredSearchResults[ResourcesDeletionStatus]] =
    deletionCache.values.map(vector => SearchResults(vector.size.toLong, vector))

  override def fetchDeletionStatus(ref: ProjectRef, uuid: UUID): IO[ProjectNotDeleted, ResourcesDeletionStatus] =
    for {
      status <- deletionCache.get(uuid)
      status <- IO.fromOption(status.filter(_.project == ref), ProjectNotDeleted(ref))
    } yield status

  override def fetch(ref: ProjectRef): IO[ProjectNotFound, ProjectResource] =
    agg
      .state(ref.toString)
      .map(_.toResource)
      .flatMap(IO.fromOption(_, ProjectNotFound(ref)))
      .named("fetchProject", moduleType)

  override def fetchAt(ref: ProjectRef, rev: Long): IO[ProjectRejection.NotFound, ProjectResource] =
    eventLog
      .fetchStateAt(persistenceId(moduleType, ref.toString), rev, Initial, Projects.next(defaultApiMappings))
      .bimap(RevisionNotFound(rev, _), _.toResource)
      .flatMap(IO.fromOption(_, ProjectNotFound(ref)))
      .named("fetchProjectAt", moduleType)

  override def fetchProject[R](
      ref: ProjectRef,
      options: Set[ProjectFetchOptions]
  )(implicit subject: Subject, rejectionMapper: Mapper[ProjectRejection, R]): IO[R, Project] =
    (IO.when(options.contains(ProjectFetchOptions.NotDeprecated))(
      organizations.fetchActiveOrganization(ref.organization).void
    ) >>
      fetch(ref).flatMap {
        case resource if options.contains(ProjectFetchOptions.NotDeleted) && resource.value.markedForDeletion =>
          IO.raiseError(ProjectIsMarkedForDeletion(ref))
        case resource if options.contains(ProjectFetchOptions.NotDeprecated) && resource.deprecated           =>
          IO.raiseError(ProjectIsDeprecated(ref))
        case resource if allQuotas.subsetOf(options)                                                          =>
          (quotas.reachedForResources(ref, subject) >> quotas.reachedForEvents(ref, subject)).as(resource.value)
        case resource if options.contains(ProjectFetchOptions.VerifyQuotaResources)                           =>
          quotas.reachedForResources(ref, subject).as(resource.value)
        case resource if options.contains(ProjectFetchOptions.VerifyQuotaEvents)                              =>
          quotas.reachedForEvents(ref, subject).as(resource.value)
        case resource                                                                                         =>
          IO.pure(resource.value)
      }).mapError(rejectionMapper.to)

  override def fetchProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectNotFound, R]): IO[R, Project] =
    fetch(ref).bimap(rejectionMapper.to, _.value)

  override def fetch(uuid: UUID): IO[ProjectNotFound, ProjectResource] =
    fetchFromCache(uuid).flatMap(fetch)

  override def fetchAt(uuid: UUID, rev: Long): IO[ProjectRejection.NotFound, ProjectResource] =
    super.fetchAt(uuid, rev).named("fetchProjectAtByUuid", moduleType)

  private def fetchFromCache(uuid: UUID): IO[ProjectNotFound, ProjectRef] =
    index.collectFirstOr { case (ref, resource) if resource.value.uuid == uuid => ref }(ProjectNotFound(uuid))

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams,
      ordering: Ordering[ProjectResource]
  ): UIO[SearchResults.UnscoredSearchResults[ProjectResource]]            =
    index.values
      .map { resources =>
        val results = resources.filter(params.matches).sorted(ordering)
        SearchResults(
          results.size.toLong,
          results.slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listProjects", moduleType)

  override def events(offset: Offset): Stream[Task, Envelope[ProjectEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[ProjectEvent]] =
    eventLog.currentEventsByTag(moduleType, offset)

  private def eval(cmd: ProjectCommand): IO[ProjectRejection, ProjectResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.ref.toString, cmd).mapError(_.value)
      resource         <- IO.fromOption(evaluationResult.state.toResource, UnexpectedInitialState(cmd.ref))
      _                <- index.put(cmd.ref, resource)
    } yield resource

}

object ProjectsImpl {

  type ProjectsAggregate =
    Aggregate[String, ProjectState, ProjectCommand, ProjectEvent, ProjectRejection]

  type ProjectsCache       = KeyValueStore[ProjectRef, ProjectResource]
  type DeletionStatusCache = KeyValueStore[UUID, ResourcesDeletionStatus]

  private val logger: Logger = Logger[ProjectsImpl]

  def cache(config: ProjectsConfig)(implicit as: ActorSystem[Nothing]): ProjectsCache = {
    implicit val cfg: KeyValueStoreConfig      = config.keyValueStore
    val clock: (Long, ProjectResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
  }

  def deletionCache(config: ProjectsConfig)(implicit as: ActorSystem[Nothing]): DeletionStatusCache = {
    implicit val cfg: KeyValueStoreConfig              = config.keyValueStore
    val clock: (Long, ResourcesDeletionStatus) => Long = (_, status) => status.updatedAt.toEpochMilli
    KeyValueStore.distributed(s"${moduleType}Deletions", clock)
  }

  private def startIndexing(
      config: ProjectsConfig,
      eventLog: EventLog[Envelope[ProjectEvent]],
      index: ProjectsCache,
      projects: Projects,
      si: Set[ScopeInitialization]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler) =
    DaemonStreamCoordinator.run(
      "ProjectsIndex",
      stream = eventLog
        .eventsByTag(moduleType, Offset.noOffset)
        .mapAsync(config.cacheIndexing.concurrency) { envelope =>
          projects
            .fetch(envelope.event.project)
            .redeemCauseWith(
              _ => IO.unit,
              { resource =>
                index.put(resource.value.ref, resource) >>
                  IO.when(!resource.deprecated && !resource.value.markedForDeletion && envelope.event.isCreated) {
                    IO.parTraverseUnordered(si)(_.onProjectCreation(resource.value, resource.createdBy)).attempt.void
                  }
              }
            )
        },
      retryStrategy = RetryStrategy.retryOnNonFatal(config.cacheIndexing.retry, logger, "projects indexing")
    )

  def aggregate(
      config: ProjectsConfig,
      organizations: Organizations,
      defaultApiMappings: ApiMappings
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO], uuidF: UUIDF): UIO[ProjectsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Projects.next(defaultApiMappings),
      evaluate = Projects.evaluate(organizations),
      tagger = EventTags.forProjectScopedEvent(moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.strategy,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
    )
  }

  /**
    * Constructs a [[Projects]] instance.
    */
  final def apply(
      agg: ProjectsAggregate,
      config: ProjectsConfig,
      eventLog: EventLog[Envelope[ProjectEvent]],
      organizations: Organizations,
      quotas: Quotas,
      scopeInitializations: Set[ScopeInitialization],
      defaultApiMappings: ApiMappings,
      cache: ProjectsCache,
      deletionCache: DeletionStatusCache
  )(implicit
      base: BaseUri,
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[Projects] =
    Task
      .delay(
        new ProjectsImpl(
          agg,
          eventLog,
          cache,
          deletionCache,
          organizations,
          quotas,
          scopeInitializations,
          defaultApiMappings
        )
      )
      .tapEval(projects => startIndexing(config, eventLog, cache, projects, scopeInitializations))

}
