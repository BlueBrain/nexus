package ch.epfl.bluebrain.nexus.delta.service.projects

import java.util.UUID

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand.{CreateProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Mapper, Organizations, ProjectResource, Projects}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.delta.service.utils.ApplyOwnerPermissions
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class ProjectsImpl private (
    agg: ProjectsAggregate,
    eventLog: EventLog[Envelope[ProjectEvent]],
    index: ProjectsCache,
    organizations: Organizations,
    applyOwnerPermissions: ApplyOwnerPermissions
)(implicit base: BaseUri)
    extends Projects {

  override def create(
      ref: ProjectRef,
      fields: ProjectFields
  )(implicit caller: Subject): IO[ProjectRejection, ProjectResource] =
    eval(
      CreateProject(
        ref,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(ref),
        fields.vocabOrGenerated(ref),
        caller
      )
    ).named("createProject", moduleType) <* applyOwnerPermissions
      .onProject(ref, caller)
      .leftMap(OwnerPermissionsFailed(ref, _))
      .named(
        "applyOwnerPermissions",
        moduleType
      )

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
        case resource                        => IO.pure(resource.value)
      }).leftMap(rejectionMapper.to)

  override def fetchProject[R](
      ref: ProjectRef
  )(implicit rejectionMapper: Mapper[ProjectRejection, R]): IO[R, Project] =
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

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ProjectEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[ProjectEvent]] =
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
      projects: Projects
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "ProjectsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            projects.fetch(envelope.event.ref).redeemCauseWith(_ => IO.unit, res => index.put(res.value.ref, res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "projects indexing")
      )
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
      tagger = (_: ProjectEvent) => Set(moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.strategy,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def apply(
      agg: ProjectsAggregate,
      eventLog: EventLog[Envelope[ProjectEvent]],
      cache: ProjectsCache,
      organizations: Organizations,
      applyOwnerPermissions: ApplyOwnerPermissions
  )(implicit base: BaseUri): ProjectsImpl =
    new ProjectsImpl(agg, eventLog, cache, organizations, applyOwnerPermissions)

  /**
    * Constructs a [[Projects]] instance.
    *
    * @param config                the projects configuration
    * @param eventLog              the event log for [[ProjectEvent]]
    * @param organizations         an instance of the organizations module
    * @param applyOwnerPermissions an instance of [[ApplyOwnerPermissions]] for project creation
    */
  final def apply(
      config: ProjectsConfig,
      eventLog: EventLog[Envelope[ProjectEvent]],
      organizations: Organizations,
      applyOwnerPermissions: ApplyOwnerPermissions
  )(implicit
      base: BaseUri,
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): UIO[Projects] =
    for {
      agg     <- aggregate(config, organizations)
      index   <- UIO.delay(cache(config))
      projects = apply(agg, eventLog, index, organizations, applyOwnerPermissions)
      _       <- UIO.delay(startIndexing(config, eventLog, index, projects))
    } yield projects

}
