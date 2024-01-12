package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUndeprecated, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storageSchema}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import fs2.Stream
import io.circe.Json
import org.typelevel.log4cats

import java.time.Instant

/**
  * Operations for handling storages
  */
final class Storages private (
    log: StorageLog,
    fetchContext: FetchContext[StorageFetchRejection],
    sourceDecoder: JsonLdSourceResolvingDecoder[StorageRejection, StorageFields],
    serviceAccount: ServiceAccount
) extends FetchStorage {

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
      source: Json
  )(implicit caller: Caller): IO[StorageResource] = {
    for {
      pc                   <- fetchContext.onCreate(projectRef)
      (iri, storageFields) <- sourceDecoder(projectRef, pc, source)
      res                  <- eval(CreateStorage(iri, projectRef, storageFields, source, caller.subject))
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
      source: Json
  )(implicit caller: Caller): IO[StorageResource] = {
    for {
      pc            <- fetchContext.onCreate(projectRef)
      iri           <- expandIri(id, pc)
      storageFields <- sourceDecoder(projectRef, pc, iri, source)
      res           <- eval(CreateStorage(iri, projectRef, storageFields, source, caller.subject))
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
  )(implicit caller: Caller): IO[StorageResource] = {
    for {
      pc    <- fetchContext.onCreate(projectRef)
      iri   <- expandIri(id, pc)
      source = storageFields.toJson(iri)
      res   <- eval(CreateStorage(iri, projectRef, storageFields, source, caller.subject))
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
      source: Json
  )(implicit caller: Caller): IO[StorageResource] =
    update(id, projectRef, rev, source, unsetPreviousDefault = true)

  private def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      source: Json,
      unsetPreviousDefault: Boolean
  )(implicit caller: Caller): IO[StorageResource] = {
    for {
      pc            <- fetchContext.onModify(projectRef)
      iri           <- expandIri(id, pc)
      storageFields <- sourceDecoder(projectRef, pc, iri, source)
      res           <- eval(UpdateStorage(iri, projectRef, storageFields, source, rev, caller.subject))
      _             <- IO.whenA(unsetPreviousDefault)(unsetPreviousDefaultIfRequired(projectRef, res))
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
  )(implicit caller: Caller): IO[StorageResource] = {
    for {
      pc    <- fetchContext.onModify(projectRef)
      iri   <- expandIri(id, pc)
      source = storageFields.toJson(iri)
      res   <- eval(UpdateStorage(iri, projectRef, storageFields, source, rev, caller.subject))
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
  )(implicit subject: Subject): IO[StorageResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(TagStorage(iri, projectRef, tagRev, tag, rev, subject))
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
  )(implicit subject: Subject): IO[StorageResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateStorage(iri, projectRef, rev, subject))
    } yield res
  }.span("deprecateStorage")

  /**
    * Undeprecate a storage
    *
    * @param id
    *   the storage identifier to expand as the id of the storage
    * @param projectRef
    *   the project where the storage belongs
    * @param rev
    *   the current revision of the storage
    */
  def undeprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[StorageResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(UndeprecateStorage(iri, projectRef, rev, subject))
    } yield res
  }.span("undeprecateStorage")

  override def fetch(id: IdSegmentRef, project: ProjectRef): IO[StorageResource] = {
    for {
      pc      <- fetchContext.onRead(project)
      iri     <- expandIri(id.value, pc)
      notFound = StorageNotFound(iri, project)
      state   <- id match {
                   case Latest(_)        => log.stateOr(project, iri, notFound)
                   case Revision(_, rev) =>
                     log.stateOr(project, iri, rev, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
                 }
    } yield state.toResource
  }.span("fetchStorage")

  private def fetchDefaults(project: ProjectRef): Stream[IO, StorageResource] =
    log
      .currentStates(Scope.Project(project), _.toResource)
      .filter(_.value.default)

  override def fetchDefault(project: ProjectRef): IO[StorageResource] = {
    for {
      defaultOpt <- fetchDefaults(project).reduce(updatedByDesc.min(_, _)).head.compile.last
      default    <- IO.fromOption(defaultOpt)(DefaultStorageNotFound(project))
    } yield default
  }.span("fetchDefaultStorage")

  /**
    * Return the existing storages in a project in a finite stream
    */
  def currentStorages(project: ProjectRef): Stream[IO, Elem[StorageState]] =
    log.currentStates(Scope.Project(project)).map { e =>
      e.withProject(e.value.project)
    }

  private def unsetPreviousDefaultIfRequired(
      project: ProjectRef,
      current: StorageResource
  ) =
    IO.whenA(current.value.default) {
      fetchDefaults(project)
        .filter(_.id != current.id)
        .evalTap { storage =>
          val source =
            storage.value.source.replace("default" -> true, false).replace("default" -> "true", false)
          val io     = update(storage.id, project, storage.rev, source, unsetPreviousDefault = false)(
            serviceAccount.caller
          )
          logFailureAndContinue(io)
        }
        .compile
        .drain
        .void
    }

  private def logFailureAndContinue[A](io: IO[A]): IO[Unit] = {
    io.onError {
      case err: StorageRejection => logger.warn(err.reason)
      case _                     => IO.unit
    }.attemptNarrow[StorageRejection]
      .void
  }

  private def eval(cmd: StorageCommand): IO[StorageResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map { case (_, state) =>
      state.toResource
    }
}

object Storages {

  type StorageLog    = ScopedEventLog[Iri, StorageState, StorageCommand, StorageEvent, StorageRejection]
  type StorageAccess = (Iri, StorageValue) => IO[Unit]

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

  implicit private[storages] val logger: log4cats.Logger[IO] = Logger[Storages]

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

    def undeprecated(e: StorageUndeprecated): Option[StorageState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = false, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: StorageCreated      => created(e)
      case e: StorageUpdated      => updated(e)
      case e: StorageTagAdded     => tagAdded(e)
      case e: StorageDeprecated   => deprecated(e)
      case e: StorageUndeprecated => undeprecated(e)
    }
  }

  private[storages] def evaluate(
      access: StorageAccess,
      fetchPermissions: IO[Set[Permission]],
      config: StorageTypeConfig,
      clock: Clock[IO]
  )(
      state: Option[StorageState],
      cmd: StorageCommand
  ): IO[StorageEvent] = {

    def isDescendantOrEqual(target: AbsolutePath, parent: AbsolutePath): Boolean =
      target == parent || target.value.descendantOf(parent.value)

    def verifyAllowedDiskVolume(id: Iri, value: StorageValue): IO[Unit] =
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

    def validateAndReturnValue(id: Iri, fields: StorageFields): IO[StorageValue] =
      for {
        value <- IO.fromOption(fields.toValue(config))(InvalidStorageType(id, fields.tpe, allowedStorageTypes))
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
          instant <- clock.realTimeInstant
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
          instant <- clock.realTimeInstant
        } yield StorageUpdated(c.id, c.project, value, c.source, s.rev + 1, instant, c.subject)
    }

    def tag(c: TagStorage) = state match {
      case None                                               => IO.raiseError(StorageNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                          => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                            =>
        clock.realTimeInstant.map(
          StorageTagAdded(c.id, c.project, s.value.tpe, c.targetRev, c.tag, s.rev + 1, _, c.subject)
        )
    }

    def deprecate(c: DeprecateStorage) = state match {
      case None                      => IO.raiseError(StorageNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   => IO.raiseError(StorageIsDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(StorageDeprecated(c.id, c.project, s.value.tpe, s.rev + 1, _, c.subject))
    }

    def undeprecate(c: UndeprecateStorage) = state match {
      case None                      => IO.raiseError(StorageNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if !s.deprecated  => IO.raiseError(StorageIsNotDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(StorageUndeprecated(c.id, c.project, s.value.tpe, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateStorage      => create(c)
      case c: UpdateStorage      => update(c)
      case c: TagStorage         => tag(c)
      case c: DeprecateStorage   => deprecate(c)
      case c: UndeprecateStorage => undeprecate(c)
    }
  }

  def definition(
      config: StorageTypeConfig,
      access: StorageAccess,
      fetchPermissions: IO[Set[Permission]],
      clock: Clock[IO]
  ): ScopedEntityDefinition[Iri, StorageState, StorageCommand, StorageEvent, StorageRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(access, fetchPermissions, config, clock)(_, _), next),
      StorageEvent.serializer,
      StorageState.serializer,
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
      fetchPermissions: IO[Set[Permission]],
      access: StorageAccess,
      xas: Transactors,
      config: StoragesConfig,
      serviceAccount: ServiceAccount,
      clock: Clock[IO]
  )(implicit
      api: JsonLdApi,
      uuidF: UUIDF
  ): IO[Storages] = {
    implicit val rcr: RemoteContextResolution = contextResolution.rcr

    StorageDecoderConfiguration.apply
      .map { implicit config =>
        new JsonLdSourceResolvingDecoder[StorageRejection, StorageFields](contexts.storages, contextResolution, uuidF)
      }
      .map { sourceDecoder =>
        new Storages(
          ScopedEventLog(definition(config.storageTypeConfig, access, fetchPermissions, clock), config.eventLog, xas),
          fetchContext,
          sourceDecoder,
          serviceAccount
        )
      }

  }

}
