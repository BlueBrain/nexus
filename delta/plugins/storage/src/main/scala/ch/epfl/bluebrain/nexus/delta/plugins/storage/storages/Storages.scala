package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.actor
import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageCommand.{CreateStorage, DeprecateStorage, TagStorage, UpdateStorage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.sdk.{Mapper, Organizations, Permissions, Projects}
import ch.epfl.bluebrain.nexus.sourcing.SnapshotStrategy.NoSnapshot
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.{Aggregate, EventLog, PersistentEventDefinition}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import java.nio.file.Path
import java.time.Instant

/**
  * Operations for handling storages
  */
final class Storages private (
    aggregate: StoragesAggregate,
    eventLog: EventLog[Envelope[StorageEvent]],
    cache: StoragesCache,
    orgs: Organizations,
    projects: Projects,
    sourceDecoder: JsonLdSourceDecoder[StorageRejection, StorageFields]
)(implicit rcr: RemoteContextResolution) {

  private val updatedByDesc: Ordering[StorageResource] = Ordering.by[StorageResource, Instant](_.updatedAt).reverse

  /**
    * Create a new storage where the id is either present on the payload or self generated
    *
    * @param projectRef the project where the storage will belong
    * @param source     the payload to create the storage
    */
  @SuppressWarnings(Array("PartialFunctionInsteadOfMatch"))
  def create(
      projectRef: ProjectRef,
      source: Secret[Json]
  )(implicit caller: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p                    <- projects.fetchActiveProject(projectRef)
      (iri, storageFields) <- sourceDecoder(p, source.value)
      res                  <- eval(CreateStorage(iri, projectRef, storageFields, source, caller), p)
      _                    <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.named("createStorage", moduleType)

  /**
    * Create a new storage with the provided id
    *
    * @param id         the storage identifier to expand as the id of the storage
    * @param projectRef the project where the storage will belong
    * @param source     the payload to create the storage
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Secret[Json]
  )(implicit caller: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p             <- projects.fetchActiveProject(projectRef)
      iri           <- expandIri(id, p)
      storageFields <- sourceDecoder(p, iri, source.value)
      res           <- eval(CreateStorage(iri, projectRef, storageFields, source, caller), p)
      _             <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.named("createStorage", moduleType)

  /**
    * Create a new storage with the provided id and the [[StorageValue]] instead of the payload
    *
    * @param id           the storage identifier to expand as the id of the storage
    * @param projectRef   the project where the storage will belong
    * @param storageFields the value of the storage
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      storageFields: StorageFields
  )(implicit caller: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p     <- projects.fetchActiveProject(projectRef)
      iri   <- expandIri(id, p)
      source = storageFields.toJson(iri)
      res   <- eval(CreateStorage(iri, projectRef, storageFields, source, caller), p)
      _     <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.named("createStorage", moduleType)

  /**
    * Update an existing storage with the passed Json ''payload''
    *
    * @param id         the storage identifier to expand as the id of the storage
    * @param projectRef the project where the storage will belong
    * @param rev        the current revision of the storage
    * @param source     the payload to update the storage
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Secret[Json]
  )(implicit caller: Subject): IO[StorageRejection, StorageResource] =
    update(id, projectRef, rev, source, unsetPreviousDefault = true)

  private def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Secret[Json],
      unsetPreviousDefault: Boolean
  )(implicit caller: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p             <- projects.fetchActiveProject(projectRef)
      iri           <- expandIri(id, p)
      storageFields <- sourceDecoder(p, iri, source.value)
      res           <- eval(UpdateStorage(iri, projectRef, storageFields, source, rev, caller), p)
      _             <- IO.when(unsetPreviousDefault)(unsetPreviousDefaultIfRequired(projectRef, res))
    } yield res
  }.named("updateStorage", moduleType)

  /**
    * Update an existing storage with the passed [[StorageValue]]
    *
    * @param id           the storage identifier to expand as the id of the storage
    * @param projectRef   the project where the storage will belong
    * @param rev          the current revision of the storage
    * @param storageFields the value of the storage
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      storageFields: StorageFields
  )(implicit caller: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p     <- projects.fetchActiveProject(projectRef)
      iri   <- expandIri(id, p)
      source = storageFields.toJson(iri)
      res   <- eval(UpdateStorage(iri, projectRef, storageFields, source, rev, caller), p)
      _     <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.named("updateStorage", moduleType)

  /**
    * Add a tag to an existing storage
    *
    * @param id         the storage identifier to expand as the id of the storage
    * @param projectRef the project where the storage belongs
    * @param tag        the tag name
    * @param tagRev     the tag revision
    * @param rev        the current revision of the storage
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- expandIri(id, p)
      res <- eval(TagStorage(iri, projectRef, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagStorage", moduleType)

  /**
    * Deprecate an existing storage
    *
    * @param id         the storage identifier to expand as the id of the storage
    * @param projectRef the project where the storage belongs
    * @param rev        the current revision of the storage
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[StorageRejection, StorageResource] = {
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- expandIri(id, p)
      res <- eval(DeprecateStorage(iri, projectRef, rev, subject), p)
    } yield res
  }.named("deprecateStorage", moduleType)

  /**
    * Fetch the storage using the ''resourceRef''
    *
    * @param resourceRef the storage reference (Latest, Revision or Tag)
    * @param project     the project where the storage belongs
    */
  def fetch[R](
      resourceRef: ResourceRef,
      project: ProjectRef
  )(implicit rejectionMapper: Mapper[StorageFetchRejection, R]): IO[R, StorageResource] =
    resourceRef match {
      case Latest(iri)           => fetch(IriSegment(iri), project).leftMap(rejectionMapper.to)
      case Revision(_, iri, rev) => fetchAt(IriSegment(iri), project, rev).leftMap(rejectionMapper.to)
      case Tag(_, iri, tag)      => fetchBy(IriSegment(iri), project, tag).leftMap(rejectionMapper.to)
    }

  /**
    * Fetch the last version of a storage
    *
    * @param id         the storage identifier to expand as the id of the storage
    * @param project the project where the storage belongs
    */
  def fetch(id: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageResource] =
    fetch(id, project, None).named("fetchStorage", moduleType)

  /**
    * Fetches the storage at a given revision
    *
    * @param id      the storage identifier to expand as the id of the storage
    * @param project the project where the storage belongs
    * @param rev     the current revision of the storage
    */
  def fetchAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  ): IO[StorageFetchRejection, StorageResource] =
    fetch(id, project, Some(rev)).named("fetchStorageAt", moduleType)

  /**
    * Fetches a storage by tag.
    *
    * @param id        the storage identifier to expand as the id of the storage
    * @param project   the project where the storage belongs
    * @param tag       the tag revision
    */
  def fetchBy(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel
  ): IO[StorageFetchRejection, StorageResource] =
    fetch(id, project, None)
      .flatMap { resource =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, project, rev).leftMap(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      }
      .named("fetchStorageByTag", moduleType)

  private def fetchDefaults(project: ProjectRef): IO[DefaultStorageNotFound, List[StorageResource]] =
    cache
      .get(project)
      .map { resources =>
        resources
          .filter(res => res.value.project == project && res.value.default && !res.deprecated)
          .toList
          .sorted(updatedByDesc)
      }

  /**
    * Fetches the default storage for a project.
    *
    * @param project   the project where to look for the default storage
    */
  def fetchDefault(project: ProjectRef): IO[DefaultStorageNotFound, StorageResource] =
    fetchDefaults(project)
      .flatMap {
        case head :: _ => IO.pure(head)
        case Nil       => IO.raiseError(DefaultStorageNotFound(project))
      }
      .named("fetchDefaultStorage", moduleType)

  /**
    * Lists storages.
    *
    * @param pagination the pagination settings
    * @param params     filter parameters for the listing
    * @param ordering   the response ordering
    * @return a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: StorageSearchParams,
      ordering: Ordering[StorageResource]
  ): UIO[UnscoredSearchResults[StorageResource]] =
    params.project
      .fold(cache.values)(cache.get)
      .map { resources =>
        val results = resources.filter(params.matches).sorted(ordering)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listStorages", moduleType)

  /**
    * List storages within a project.
    *
    * @param projectRef the project the storages belong to
    * @param pagination the pagination settings
    * @param params     filter parameters
    * @param ordering   the response ordering
    */
  def list(
      projectRef: ProjectRef,
      pagination: FromPagination,
      params: StorageSearchParams,
      ordering: Ordering[StorageResource]
  ): UIO[UnscoredSearchResults[StorageResource]] =
    list(pagination, params.copy(project = Some(projectRef)), ordering)

  /**
    * A non terminating stream of events for storages. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param projectRef the project reference where the storage belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[StorageRejection, Stream[Task, Envelope[StorageEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(eventLog.eventsByTag(s"${Projects.moduleType}=$projectRef", offset))

  /**
    * A non terminating stream of events for storages. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param organization the organization label reference where the storage belongs
    * @param offset       the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[StorageEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(eventLog.eventsByTag(s"${Organizations.moduleType}=$organization", offset))

  /**
    * A non terminating stream of events for storages. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[StorageEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def unsetPreviousDefaultIfRequired(
      project: ProjectRef,
      current: StorageResource
  )(implicit caller: Subject) =
    IO.when(current.value.default)(
      fetchDefaults(project).map(_.filter(_.id != current.id)).flatMap { resources =>
        resources.traverse { storage =>
          val source = storage.value.source.map(_.replace("default" -> true, false).replace("default" -> "true", false))
          val io     = update(IriSegment(storage.id), project, storage.rev, source, unsetPreviousDefault = false)
          logFailureAndContinue(io)
        } >> UIO.unit
      }
    )

  private def logFailureAndContinue[A](io: IO[StorageRejection, A]): UIO[Unit] =
    io.mapError(err => logger.warn(err.reason)).attempt >> UIO.unit

  private def fetch(
      id: IdSegment,
      project: ProjectRef,
      rev: Option[Long]
  ): IO[StorageFetchRejection, StorageResource] =
    for {
      p     <- projects.fetchProject(project)
      iri   <- expandIri(id, p)
      state <- rev.fold(currentState(project, iri))(stateAt(project, iri, _))
      res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), StorageNotFound(iri, project))
    } yield res

  private def eval(cmd: StorageCommand, project: Project): IO[StorageRejection, StorageResource] =
    for {
      evaluationResult <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      resourceOpt       = evaluationResult.state.toResource(project.apiMappings, project.base)
      res              <- IO.fromOption(resourceOpt, UnexpectedInitialState(cmd.id, project.ref))
      _                <- cache.put(cmd.project, cmd.id, res)
    } yield res

  private def currentState(project: ProjectRef, iri: Iri): IO[StorageFetchRejection, StorageState] =
    aggregate.state(identifier(project, iri))

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) =
    EventLogUtils
      .fetchStateAt(eventLog, persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .leftMap(RevisionNotFound(rev, _))

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"
}

object Storages {

  private[storages] type StoragesAggregate =
    Aggregate[String, StorageState, StorageCommand, StorageEvent, StorageRejection]
  private[storages] type StoragesCache     = CompositeKeyValueStore[ProjectRef, Iri, StorageResource]
  private[storages] type StorageAccess     = (Iri, StorageValue) => IO[StorageNotAccessible, Unit]

  /**
    * The storages module type.
    */
  final val moduleType: String = "storage"

  val context: ContextValue = ContextValue(contexts.storages)

  val expandIri: ExpandIri[InvalidStorageId] = new ExpandIri(InvalidStorageId.apply)

  private[storages] val logger: Logger = Logger[Storages]

  /**
    * Constructs a Storages instance
    *
    * @param config      the storage configuration
    * @param eventLog    the event log for StorageEvent
    * @param permissions a permissions operations bundle
    * @param orgs        a organizations operations bundle
    * @param projects    a projects operations bundle
    */
  @SuppressWarnings(Array("MaxParameters"))
  final def apply(
      config: StoragesConfig,
      eventLog: EventLog[Envelope[StorageEvent]],
      permissions: Permissions,
      orgs: Organizations,
      projects: Projects
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing],
      rcr: RemoteContextResolution
  ): UIO[Storages] = {
    implicit val classicAs: actor.ActorSystem = as.classicSystem
    apply(config, eventLog, permissions, orgs, projects, StorageAccess.apply(_, _))
  }

  @SuppressWarnings(Array("MaxParameters"))
  final def apply(
      config: StoragesConfig,
      eventLog: EventLog[Envelope[StorageEvent]],
      permissions: Permissions,
      orgs: Organizations,
      projects: Projects,
      access: StorageAccess
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing],
      rcr: RemoteContextResolution
  ): UIO[Storages] =
    for {
      agg          <- aggregate(config, access, permissions)
      index        <- UIO.delay(cache(config))
      sourceDecoder = new JsonLdSourceDecoder[StorageRejection, StorageFields](contexts.storages, uuidF)
      storages      = new Storages(agg, eventLog, index, orgs, projects, sourceDecoder)
      _            <- UIO.delay(startIndexing(config, eventLog, index, storages))
    } yield storages

  private def cache(config: StoragesConfig)(implicit as: ActorSystem[Nothing]): StoragesCache = {
    implicit val cfg: KeyValueStoreConfig      = config.keyValueStore
    val clock: (Long, StorageResource) => Long = (_, resource) => resource.rev
    CompositeKeyValueStore(moduleType, clock)
  }

  private def startIndexing(
      config: StoragesConfig,
      eventLog: EventLog[Envelope[StorageEvent]],
      index: StoragesCache,
      storages: Storages
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "StorageIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            storages
              .fetch(IriSegment(envelope.event.id), envelope.event.project)
              .redeemCauseWith(_ => IO.unit, res => index.put(res.value.project, res.value.id, res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "storages indexing")
      )
    )

  private def aggregate(config: StoragesConfig, access: StorageAccess, permissions: Permissions)(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ) = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(access, permissions, config.storageTypeConfig),
      tagger = (event: StorageEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${event.project}",
          s"${Organizations.moduleType}=${event.project.organization}"
        ),
      snapshotStrategy = NoSnapshot,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private[storages] def next(state: StorageState, event: StorageEvent): StorageState = {

    // format: off
    def created(e: StorageCreated): StorageState = state match {
      case Initial     =>
        Current(e.id, e.project, e.value, e.source, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: StorageUpdated): StorageState = state match {
      case Initial    => Initial
      case s: Current =>
        s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: StorageTagAdded): StorageState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: StorageDeprecated): StorageState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: StorageCreated    => created(e)
      case e: StorageUpdated    => updated(e)
      case e: StorageTagAdded   => tagAdded(e)
      case e: StorageDeprecated => deprecated(e)
    }
  }

  private[storages] def evaluate(
      access: StorageAccess,
      permissions: Permissions,
      config: StorageTypeConfig
  )(
      state: StorageState,
      cmd: StorageCommand
  )(implicit clock: Clock[UIO]): IO[StorageRejection, StorageEvent] = {

    def isDescendantOrEqual(target: Path, parent: Path): Boolean =
      target == parent || target.descendantOf(parent)

    def verifyAllowedDiskVolume(id: Iri, value: StorageValue): IO[StorageNotAccessible, Unit] =
      value match {
        case d: DiskStorageValue if !config.disk.allowedVolumes.exists(isDescendantOrEqual(d.volume, _)) =>
          val err = s"Volume '${d.volume}' not allowed. Allowed volumes: '${config.disk.allowedVolumes.mkString(",")}'"
          IO.raiseError(StorageNotAccessible(id, err))
        case _                                                                                           => IO.unit
      }

    val crypto = config.encryption.crypto

    val allowedStorageTypes: Set[StorageType] =
      Set(StorageType.DiskStorage) ++
        config.amazon.as(StorageType.S3Storage) ++
        config.remoteDisk.as(StorageType.RemoteDiskStorage)

    def verifyCrypto(value: StorageValue) =
      value.secrets.toList
        .foldM(()) { case (_, Secret(value)) =>
          crypto.encrypt(value).flatMap(crypto.decrypt).as(())
        }
        .leftMap(InvalidEncryptionSecrets(value.tpe, _))

    def validateAndReturnValue(id: Iri, fields: StorageFields): IO[StorageRejection, StorageValue] =
      for {
        value <- IO.fromOption(fields.toValue(config), InvalidStorageType(id, fields.tpe, allowedStorageTypes))
        _     <- IO.fromEither(verifyCrypto(value))
        _     <- validatePermissions(fields)
        _     <- access(id, value)
        _     <- verifyAllowedDiskVolume(id, value)
        _     <- validateFileSize(id, fields.maxFileSize, value.maxFileSize)
      } yield value

    def validatePermissions(value: StorageFields) =
      if (value.readPermission.isEmpty && value.writePermission.isEmpty)
        IO.unit
      else {
        val storagePerms = Set.empty[Permission] ++ value.readPermission ++ value.writePermission
        permissions.fetch.flatMap {
          case perms if storagePerms.subsetOf(perms.value.permissions) => IO.unit
          case perms                                                   => IO.raiseError(PermissionsAreNotDefined(storagePerms -- perms.value.permissions))
        }
      }

    def validateFileSize(id: Iri, payloadSize: Option[Long], maxFileSize: Long) =
      payloadSize match {
        case Some(size) if size <= 0 || size > maxFileSize => IO.raiseError(InvalidMaxFileSize(id, size, maxFileSize))
        case _                                             => IO.unit
      }

    def create(c: CreateStorage) = state match {
      case Initial =>
        for {
          value   <- validateAndReturnValue(c.id, c.fields)
          instant <- IOUtils.instant
        } yield StorageCreated(c.id, c.project, value, c.source, 1L, instant, c.subject)
      case _       =>
        IO.raiseError(StorageAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateStorage) = state match {
      case Initial                                   => IO.raiseError(StorageNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev              => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                => IO.raiseError(StorageIsDeprecated(c.id))
      case s: Current if c.fields.tpe != s.value.tpe =>
        IO.raiseError(DifferentStorageType(s.id, c.fields.tpe, s.value.tpe))
      case s: Current                                =>
        for {
          value   <- validateAndReturnValue(c.id, c.fields)
          instant <- IOUtils.instant
        } yield StorageUpdated(c.id, c.project, value, c.source, s.rev + 1L, instant, c.subject)
    }

    def tag(c: TagStorage) = state match {
      case Initial                                                => IO.raiseError(StorageNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(StorageIsDeprecated(c.id))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(StorageTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1L, _, c.subject))
    }

    def deprecate(c: DeprecateStorage) = state match {
      case Initial                      => IO.raiseError(StorageNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   => IO.raiseError(StorageIsDeprecated(c.id))
      case s: Current                   => IOUtils.instant.map(StorageDeprecated(c.id, c.project, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateStorage    => create(c)
      case c: UpdateStorage    => update(c)
      case c: TagStorage       => tag(c)
      case c: DeprecateStorage => deprecate(c)
    }
  }

}
