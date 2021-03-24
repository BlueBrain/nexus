package ch.epfl.bluebrain.nexus.delta.service.projects

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{Mapper, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand.{CreateProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.service.syntax._
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
    organizations: Organizations,
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

  override def fetch(ref: ProjectRef): IO[ProjectNotFound, ProjectResource] =
    agg
      .state(ref.toString)
      .map(_.toResource)
      .flatMap(IO.fromOption(_, ProjectNotFound(ref)))
      .named("fetchProject", moduleType)

  override def fetchAt(ref: ProjectRef, rev: Long): IO[ProjectRejection.NotFound, ProjectResource] =
    eventLog
      .fetchStateAt(persistenceId(moduleType, ref.toString), rev, Initial, Projects.next)
      .bimap(RevisionNotFound(rev, _), _.toResource)
      .flatMap(IO.fromOption(_, ProjectNotFound(ref)))
      .named("fetchProjectAt", moduleType)

  override def fetchActiveProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectRejection, R]): IO[R, Project] =
    (organizations.fetchActiveOrganization(ref.organization) >>
      fetch(ref).flatMap {
        case resource if resource.deprecated => IO.raiseError(ProjectIsDeprecated(ref))
        case resource                        => IO.pure(resource.value.copy(apiMappings = defaultApiMappings + resource.value.apiMappings))
      }).mapError(rejectionMapper.to)

  override def fetchProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectNotFound, R]): IO[R, Project] =
    fetch(ref).bimap(rejectionMapper.to, r => r.value.copy(apiMappings = defaultApiMappings + r.value.apiMappings))

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

  type ProjectsCache = KeyValueStore[ProjectRef, ProjectResource]

  private val logger: Logger = Logger[ProjectsImpl]

  /**
    * Creates a new projects cache.
    */
  private def cache(config: ProjectsConfig)(implicit as: ActorSystem[Nothing]): ProjectsCache = {
    implicit val cfg: KeyValueStoreConfig      = config.keyValueStore
    val clock: (Long, ProjectResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
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
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.cacheIndexing.concurrency) { envelope =>
            projects
              .fetch(envelope.event.project)
              .redeemCauseWith(
                _ => IO.unit,
                { resource =>
                  index.put(resource.value.ref, resource) >>
                    IO.when(!resource.deprecated && envelope.event.isCreated) {
                      IO.parTraverseUnordered(si)(_.onProjectCreation(resource.value, resource.createdBy)).attempt.void
                    }
                }
              )
          }
      ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.cacheIndexing.retry, logger, "projects indexing")
    )

  private def aggregate(config: ProjectsConfig, organizations: Organizations)(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO],
      uuidF: UUIDF
  ): UIO[ProjectsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Projects.next,
      evaluate = Projects.evaluate(organizations),
      tagger = EventTags.forProjectScopedEvent(moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.strategy,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
      // TODO: configure the number of shards
    )
  }

  private def apply(
      agg: ProjectsAggregate,
      eventLog: EventLog[Envelope[ProjectEvent]],
      cache: ProjectsCache,
      organizations: Organizations,
      scopeInitializations: Set[ScopeInitialization],
      defaultApiMappings: ApiMappings
  )(implicit base: BaseUri): ProjectsImpl =
    new ProjectsImpl(agg, eventLog, cache, organizations, scopeInitializations, defaultApiMappings)

  /**
    * Constructs a [[Projects]] instance.
    *
    * @param config               the projects configuration
    * @param eventLog             the event log for [[ProjectEvent]]
    * @param organizations        an instance of the organizations module
    * @param scopeInitializations the collection of registered scope initializations
    * @param defaultApiMappings   the default api mappings
    */
  final def apply(
      config: ProjectsConfig,
      eventLog: EventLog[Envelope[ProjectEvent]],
      organizations: Organizations,
      scopeInitializations: Set[ScopeInitialization],
      defaultApiMappings: ApiMappings
  )(implicit
      base: BaseUri,
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): Task[Projects] =
    for {
      agg     <- aggregate(config, organizations)
      index   <- UIO.delay(cache(config))
      projects = apply(agg, eventLog, index, organizations, scopeInitializations, defaultApiMappings)
      _       <- startIndexing(config, eventLog, index, projects, scopeInitializations)
    } yield projects

}
