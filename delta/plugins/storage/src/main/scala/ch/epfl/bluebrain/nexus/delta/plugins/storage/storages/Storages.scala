package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.kernel.{Mapper, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storageSchema}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEntityDefinition, Predicate, ScopedEventLog, StateMachine}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

import java.time.Instant

/**
  * Operations for handling storages
  */
final class Storages private (
    log: StorageLog,
    fetchContext: FetchContext[StorageFetchRejection],
    sourceDecoder: JsonLdSourceResolvingDecoder[StorageRejection, StorageFields],
    serviceAccount: ServiceAccount
) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  private val updatedByDesc: Ordering[StorageResource] = Ordering.by[StorageResource, Instant](_.updatedAt).reverse

  /**
    * Create a new storage where the id is either present on the payload or self generated
    *
    * @param projectRef
    *   the project where the storage will belong
    * @param source
    *   the payload to create the storage
    */
  def create(
      projectRef: ProjectRef,
      source: Secret[Json]
  )(implicit caller: Caller): IO[StorageRejection, StorageResource] = {
    for {
      pc                   <- fetchContext.onCreate(projectRef)
      (iri, storageFields) <- sourceDecoder(projectRef, pc, source.value)
      res                  <- eval(CreateStorage(iri, projectRef, storageFields, source, caller.subject), pc)
      _                    <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.span("createStorage")

  /**
    * Create a new storage with the provided id
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage will belong
    * @param source
    *   the payload to create the storage
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Secret[Json]
  )(implicit caller: Caller): IO[StorageRejection, StorageResource] = {
    for {
      pc            <- fetchContext.onCreate(projectRef)
      iri           <- expandIri(id, pc)
      storageFields <- sourceDecoder(projectRef, pc, iri, source.value)
      res           <- eval(CreateStorage(iri, projectRef, storageFields, source, caller.subject), pc)
      _             <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.span("createStorage")

  /**
    * Create a new storage with the provided id and the [[StorageValue]] instead of the payload
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage will belong
    * @param storageFields
    *   the value of the storage
    */
  def create(
      id: IdSegment,
      projectRef: ProjectRef,
      storageFields: StorageFields
  )(implicit caller: Caller): IO[StorageRejection, StorageResource] = {
    for {
      pc    <- fetchContext.onCreate(projectRef)
      iri   <- expandIri(id, pc)
      source = storageFields.toJson(iri)
      res   <- eval(CreateStorage(iri, projectRef, storageFields, source, caller.subject), pc)
      _     <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.span("createStorage")

  /**
    * Update an existing storage with the passed Json ''payload''
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage will belong
    * @param rev
    *   the current revision of the storage
    * @param source
    *   the payload to update the storage
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      source: Secret[Json]
  )(implicit caller: Caller): IO[StorageRejection, StorageResource] =
    update(id, projectRef, rev, source, unsetPreviousDefault = true)

  private def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      source: Secret[Json],
      unsetPreviousDefault: Boolean
  )(implicit caller: Caller): IO[StorageRejection, StorageResource] = {
    for {
      pc            <- fetchContext.onModify(projectRef)
      iri           <- expandIri(id, pc)
      storageFields <- sourceDecoder(projectRef, pc, iri, source.value)
      res           <- eval(UpdateStorage(iri, projectRef, storageFields, source, rev, caller.subject), pc)
      _             <- IO.when(unsetPreviousDefault)(unsetPreviousDefaultIfRequired(projectRef, res))
    } yield res
  }.span("updateStorage")

  /**
    * Update an existing storage with the passed [[StorageValue]]
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage will belong
    * @param rev
    *   the current revision of the storage
    * @param storageFields
    *   the value of the storage
    */
  def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      storageFields: StorageFields
  )(implicit caller: Caller): IO[StorageRejection, StorageResource] = {
    for {
      pc    <- fetchContext.onModify(projectRef)
      iri   <- expandIri(id, pc)
      source = storageFields.toJson(iri)
      res   <- eval(UpdateStorage(iri, projectRef, storageFields, source, rev, caller.subject), pc)
      _     <- unsetPreviousDefaultIfRequired(projectRef, res)
    } yield res
  }.span("updateStorage")

  /**
    * Add a tag to an existing storage
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage belongs
    * @param tag
    *   the tag name
    * @param tagRev
    *   the tag revision
    * @param rev
    *   the current revision of the storage
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit subject: Subject): IO[StorageRejection, StorageResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(TagStorage(iri, projectRef, tagRev, tag, rev, subject), pc)
    } yield res
  }.span("tagStorage")

  /**
    * Deprecate an existing storage
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage belongs
    * @param rev
    *   the current revision of the storage
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[StorageRejection, StorageResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateStorage(iri, projectRef, rev, subject), pc)
    } yield res
  }.span("deprecateStorage")

  /**
    * Fetch the storage using the ''resourceRef''
    *
    * @param resourceRef
    *   the storage reference (Latest, Revision or Tag)
    * @param project
    *   the project where the storage belongs
    */
  def fetch[R](
      resourceRef: ResourceRef,
      project: ProjectRef
  )(implicit rejectionMapper: Mapper[StorageFetchRejection, R]): IO[R, StorageResource] =
    fetch(IdSegmentRef(resourceRef), project).mapError(rejectionMapper.to)

  /**
    * Fetch the last version of a storage
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the storage with its optional rev/tag
    * @param project
    *   the project where the storage belongs
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[StorageFetchRejection, StorageResource] = {
    for {
      pc      <- fetchContext.onRead(project)
      iri     <- expandIri(id.value, pc)
      notFound = StorageNotFound(iri, project)
      state   <- id match {
                   case Latest(_)        => log.stateOr(project, iri, notFound)
                   case Revision(_, rev) =>
                     log.stateOr(project, iri, rev.toInt, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
                 }
    } yield state.toResource(pc.apiMappings, pc.base)
  }.span("fetchStorage")

  private def fetchDefaults(project: ProjectRef): IO[StorageFetchRejection, Stream[Task, StorageResource]] =
    fetchContext.onRead(project).map { pc =>
      log.currentStates(Predicate.Project(project), _.toResource(pc.apiMappings, pc.base)).filter(_.value.default)
    }

  /**
    * Fetches the default storage for a project.
    *
    * @param project
    *   the project where to look for the default storage
    */
  def fetchDefault(project: ProjectRef): IO[StorageRejection, StorageResource] = {
    for {
      defaults   <- fetchDefaults(project)
      defaultOpt <- defaults.reduce(updatedByDesc.min(_, _)).head.compile.last.hideErrors
      default    <- IO.fromOption(defaultOpt, DefaultStorageNotFound(project))
    } yield default
  }.span("fetchDefaultStorage")

  /**
    * Lists storages.
    *
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters for the listing
    * @param ordering
    *   the response ordering
    * @return
    *   a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: StorageSearchParams,
      ordering: Ordering[StorageResource]
  ): UIO[UnscoredSearchResults[StorageResource]] = {
    val predicate = params.project.fold[Predicate](Predicate.Root)(ref => Predicate.Project(ref))
    SearchResults(
      log.currentStates(predicate, identity(_)).evalMapFilter[Task, StorageResource] { state =>
        fetchContext.cacheOnReads
          .onRead(state.project)
          .redeemWith(
            _ => UIO.none,
            pc => {
              val res = state.toResource(pc.apiMappings, pc.base)
              params.matches(res).map(Option.when(_)(res))
            }
          )
      },
      pagination,
      ordering
    )
  }.span("listStorages")

  /**
    * List storages within a project.
    *
    * @param projectRef
    *   the project the storages belong to
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters
    * @param ordering
    *   the response ordering
    */
  def list(
      projectRef: ProjectRef,
      pagination: FromPagination,
      params: StorageSearchParams,
      ordering: Ordering[StorageResource]
  ): UIO[UnscoredSearchResults[StorageResource]] =
    list(pagination, params.copy(project = Some(projectRef)), ordering)

  private def unsetPreviousDefaultIfRequired(
      project: ProjectRef,
      current: StorageResource
  ) =
    IO.when(current.value.default)(
      fetchDefaults(project).map(_.filter(_.id != current.id)).flatMap { resources =>
        resources
          .evalTap { storage =>
            val source =
              storage.value.source.map(_.replace("default" -> true, false).replace("default" -> "true", false))
            val io     = update(storage.id, project, storage.rev.toInt, source, unsetPreviousDefault = false)(
              serviceAccount.caller
            )
            logFailureAndContinue(io)
          }
          .compile
          .drain
          .hideErrors >> UIO.unit
      }
    )

  private def logFailureAndContinue[A](io: IO[StorageRejection, A]): UIO[Unit] =
    io.mapError(err => logger.warn(err.reason)).attempt >> UIO.unit

  private def eval(cmd: StorageCommand, pc: ProjectContext): IO[StorageRejection, StorageResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource(pc.apiMappings, pc.base))

}

object Storages {

  type StorageLog    = ScopedEventLog[Iri, StorageState, StorageCommand, StorageEvent, StorageRejection]
  type StorageAccess = (Iri, StorageValue) => IO[StorageNotAccessible, Unit]

  /**
    * The storage entity type.
    */
  final val entityType: EntityType = EntityType("storage")

  val context: ContextValue = ContextValue(contexts.storages)

  val expandIri: ExpandIri[InvalidStorageId] = new ExpandIri(InvalidStorageId.apply)

  /**
    * The default Storage API mappings
    */
  val mappings: ApiMappings = ApiMappings("storage" -> storageSchema, "defaultStorage" -> defaultStorageId)

  implicit private[storages] val logger: Logger = Logger[Storages]

  private[storages] def next(state: Option[StorageState], event: StorageEvent): Option[StorageState] = {

    def created(e: StorageCreated): Option[StorageState] =
      Option.when(state.isEmpty) {
        StorageState(
          e.id,
          e.project,
          e.value,
          e.source,
          Tags.empty,
          e.rev,
          deprecated = false,
          e.instant,
          e.subject,
          e.instant,
          e.subject
        )
      }

    def updated(e: StorageUpdated): Option[StorageState] = state.map { s =>
      s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: StorageTagAdded): Option[StorageState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def deprecated(e: StorageDeprecated): Option[StorageState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
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
      fetchPermissions: UIO[Set[Permission]],
      config: StorageTypeConfig,
      crypto: Crypto
  )(
      state: Option[StorageState],
      cmd: StorageCommand
  )(implicit clock: Clock[UIO]): IO[StorageRejection, StorageEvent] = {

    def isDescendantOrEqual(target: AbsolutePath, parent: AbsolutePath): Boolean =
      target == parent || target.value.descendantOf(parent.value)

    def verifyAllowedDiskVolume(id: Iri, value: StorageValue): IO[StorageNotAccessible, Unit] =
      value match {
        case d: DiskStorageValue if !config.disk.allowedVolumes.exists(isDescendantOrEqual(d.volume, _)) =>
          val err = s"Volume '${d.volume}' not allowed. Allowed volumes: '${config.disk.allowedVolumes.mkString(",")}'"
          IO.raiseError(StorageNotAccessible(id, err))
        case _                                                                                           => IO.unit
      }

    val allowedStorageTypes: Set[StorageType] =
      Set(StorageType.DiskStorage) ++
        config.amazon.as(StorageType.S3Storage) ++
        config.remoteDisk.as(StorageType.RemoteDiskStorage)

    def verifyCrypto(value: StorageValue) =
      value.secrets.toList
        .foldM(()) { case (_, Secret(value)) =>
          crypto.encrypt(value).flatMap(crypto.decrypt).toEither.void
        }
        .leftMap(t => InvalidEncryptionSecrets(value.tpe, t.getMessage))

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
        fetchPermissions.flatMap {
          case perms if storagePerms.subsetOf(perms) => IO.unit
          case perms                                 => IO.raiseError(PermissionsAreNotDefined(storagePerms -- perms))
        }
      }

    def validateFileSize(id: Iri, payloadSize: Option[Long], maxFileSize: Long) =
      payloadSize match {
        case Some(size) if size <= 0 || size > maxFileSize => IO.raiseError(InvalidMaxFileSize(id, size, maxFileSize))
        case _                                             => IO.unit
      }

    def create(c: CreateStorage) = state match {
      case None    =>
        for {
          value   <- validateAndReturnValue(c.id, c.fields)
          instant <- IOUtils.instant
        } yield StorageCreated(c.id, c.project, value, c.source, 1, instant, c.subject)
      case Some(_) =>
        IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateStorage) = state match {
      case None                                   => IO.raiseError(StorageNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev              => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated                => IO.raiseError(StorageIsDeprecated(c.id))
      case Some(s) if c.fields.tpe != s.value.tpe =>
        IO.raiseError(DifferentStorageType(s.id, c.fields.tpe, s.value.tpe))
      case Some(s)                                =>
        for {
          value   <- validateAndReturnValue(c.id, c.fields)
          instant <- IOUtils.instant
        } yield StorageUpdated(c.id, c.project, value, c.source, s.rev + 1, instant, c.subject)
    }

    def tag(c: TagStorage) = state match {
      case None                                               => IO.raiseError(StorageNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                          => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                            =>
        IOUtils.instant.map(StorageTagAdded(c.id, c.project, s.value.tpe, c.targetRev, c.tag, s.rev + 1, _, c.subject))
    }

    def deprecate(c: DeprecateStorage) = state match {
      case None                      => IO.raiseError(StorageNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   => IO.raiseError(StorageIsDeprecated(c.id))
      case Some(s)                   => IOUtils.instant.map(StorageDeprecated(c.id, c.project, s.value.tpe, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateStorage    => create(c)
      case c: UpdateStorage    => update(c)
      case c: TagStorage       => tag(c)
      case c: DeprecateStorage => deprecate(c)
    }
  }

  def definition(
      config: StorageTypeConfig,
      access: StorageAccess,
      fetchPermissions: UIO[Set[Permission]],
      crypto: Crypto
  )(implicit clock: Clock[UIO]): ScopedEntityDefinition[Iri, StorageState, StorageCommand, StorageEvent, StorageRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(access, fetchPermissions, config, crypto), next),
      StorageEvent.serializer(crypto),
      StorageState.serializer(crypto),
      Tagger[StorageEvent](
        {
          case r: StorageTagAdded => Some(r.tag -> r.targetRev)
          case _                  => None
        },
        { _ =>
          None
        }
      ),
      _ => None,
      onUniqueViolation = (id: Iri, c: StorageCommand) =>
        c match {
          case c: CreateStorage => ResourceAlreadyExists(id, c.project)
          case c                => IncorrectRev(c.rev, c.rev + 1)
        }
    )

  /**
    * Constructs a Storages instance
    */
  def apply(
      fetchContext: FetchContext[StorageFetchRejection],
      contextResolution: ResolverContextResolution,
      fetchPermissions: UIO[Set[Permission]],
      access: StorageAccess,
      crypto: Crypto,
      xas: Transactors,
      config: StoragesConfig,
      serviceAccount: ServiceAccount
  )(implicit
      api: JsonLdApi,
      clock: Clock[UIO],
      uuidF: UUIDF
  ): Task[Storages] = {
    Task
      .delay(
        new JsonLdSourceResolvingDecoder[StorageRejection, StorageFields](contexts.storages, contextResolution, uuidF)
      )
      .map { sourceDecoder =>
        new Storages(
          ScopedEventLog(definition(config.storageTypeConfig, access, fetchPermissions, crypto), config.eventLog, xas),
          fetchContext,
          sourceDecoder,
          serviceAccount
        )
      }

  }

}
