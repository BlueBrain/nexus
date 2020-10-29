package ch.epfl.bluebrain.nexus.delta.service.projects

import java.util.UUID

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand.{CreateProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{OwnerPermissionsFailed, RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Organizations, ProjectResource, Projects}
import ch.epfl.bluebrain.nexus.delta.service.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{moduleType, ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.service.syntax._
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
    acls: Acls,
    ownerPermissions: Set[Permission],
    serviceAccount: Identity.Subject
)(implicit base: BaseUri)
    extends Projects {

  override def create(ref: ProjectRef, fields: ProjectFields)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(
      CreateProject(
        ref,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(ref),
        fields.vocabOrGenerated(ref),
        caller
      )
    ).named("createProject", moduleType) <* applyOwnerPermissions(ref, caller).named(
      "applyOwnerPermissions",
      moduleType
    )

  private def applyOwnerPermissions(ref: ProjectRef, subject: Identity.Subject): IO[OwnerPermissionsFailed, Unit] = {
    val projectAddress = AclAddress.Project(ref.organization, ref.project)

    def applyMissing(collection: AclCollection) = {
      val currentPermissions = collection.value.foldLeft(Set.empty[Permission]) { case (acc, (_, acl)) =>
        acc ++ acl.value.permissions
      }

      if (ownerPermissions.subsetOf(currentPermissions))
        IO.unit
      else {
        val rev = collection.value.get(projectAddress).fold(0L)(_.rev)
        acls.append(projectAddress, Acl(subject -> ownerPermissions), rev)(serviceAccount) >> IO.unit
      }
    }

    acls.fetchWithAncestors(projectAddress).flatMap(applyMissing).leftMap(OwnerPermissionsFailed(ref, _))
  }

  override def update(ref: ProjectRef, rev: Long, fields: ProjectFields)(implicit
      caller: Identity.Subject
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

  override def deprecate(ref: ProjectRef, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(DeprecateProject(ref, rev, caller)).named("deprecateProject", moduleType)

  override def fetch(ref: ProjectRef): UIO[Option[ProjectResource]] =
    agg.state(ref.toString).map(_.toResource).named("fetchProject", moduleType)

  override def fetchAt(ref: ProjectRef, rev: Long): IO[ProjectRejection.RevisionNotFound, Option[ProjectResource]] =
    eventLog
      .fetchStateAt(
        persistenceId(moduleType, ref.toString),
        rev,
        Initial,
        Projects.next
      )
      .bimap(RevisionNotFound(rev, _), _.toResource)
      .named("fetchProjectAt", moduleType)

  override def fetch(uuid: UUID): UIO[Option[ProjectResource]] =
    fetchFromCache(uuid).flatMap {
      case Some(ref) => fetch(ref)
      case None      => UIO.pure(None)
    }

  override def fetchAt(uuid: UUID, rev: Long): IO[RevisionNotFound, Option[ProjectResource]] =
    super.fetchAt(uuid, rev).named("fetchProjectAtByUuid", moduleType)

  private def fetchFromCache(uuid: UUID): UIO[Option[ProjectRef]] =
    index.collectFirst { case (ref, resource) if resource.value.uuid == uuid => ref }

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[ProjectResource]]    =
    index.values
      .map { resources =>
        val results = resources.filter(params.matches).toVector.sortBy(_.createdAt)
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

  /**
    * The moduleType.
    */
  final val moduleType: String = "project"

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
            projects.fetch(envelope.event.ref).flatMap {
              case Some(project) => index.put(project.id, project)
              case None          => UIO.unit
            }
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
      evaluate = Projects.evaluate(organizations.fetch),
      tagger = (_: ProjectEvent) => Set(moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.combinedStrategy(
        SnapshotStrategy.SnapshotPredicate((state: ProjectState, _: ProjectEvent, _: Long) => state.deprecated)
      ),
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
      acls: Acls,
      ownerPermissions: Set[Permission],
      serviceAccount: Identity.Subject
  )(implicit base: BaseUri): ProjectsImpl =
    new ProjectsImpl(agg, eventLog, cache, acls, ownerPermissions, serviceAccount)

  /**
    * Constructs a [[Projects]] instance.
    *
    * @param config           the projects configuration
    * @param eventLog         the event log for [[ProjectEvent]]
    * @param organizations    an instance of the organizations module
    * @param acls             an instance of the acl module
    * @param ownerPermissions the owner permissions to be present at project creation
    * @param serviceAccount   ther service account to apply owner permissions when needed
    */
  final def apply(
      config: ProjectsConfig,
      eventLog: EventLog[Envelope[ProjectEvent]],
      organizations: Organizations,
      acls: Acls,
      ownerPermissions: Set[Permission],
      serviceAccount: Identity.Subject
  )(implicit
      base: BaseUri,
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): UIO[Projects] =
    for {
      agg     <- aggregate(config, organizations)
      index    = cache(config)
      projects = apply(agg, eventLog, index, acls, ownerPermissions, serviceAccount)
      _       <- UIO.delay(startIndexing(config, eventLog, index, projects))
    } yield projects

}
