package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.{ContentType, HttpEntity, Uri}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => fileSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageIsDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{FetchAttributes, FetchFile, LinkFile, SaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import java.util.UUID

/**
  * Operations for handling files
  */
final class Files(
    formDataExtractor: FormDataExtractor,
    log: FilesLog,
    aclCheck: AclCheck,
    fetchContext: FetchContext[FileRejection],
    storages: Storages,
    storagesStatistics: StoragesStatistics
)(implicit config: StorageTypeConfig, client: HttpClient, uuidF: UUIDF, system: ClassicActorSystem) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  // format: off
  private val testStorageRef = ResourceRef.Revision(iri"http://localhost/test", 1)
  private val testStorageType = StorageType.DiskStorage
  private val testAttributes = FileAttributes(UUID.randomUUID(), "http://localhost", Uri.Path.Empty, "", None, 0, ComputedDigest(DigestAlgorithm.default, "value"), Client)
  // format: on

  /**
    * Create a new file where the id is self generated
    *
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param entity
    *   the http FormData entity
    */
  def create(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      entity: HttpEntity
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      pc                    <- fetchContext.onCreate(projectRef)
      iri                   <- generateId(pc)
      _                     <- test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, projectRef, pc)
      attributes            <- extractFileAttributes(iri, entity, storage)
      res                   <- eval(CreateFile(iri, projectRef, storageRef, storage.tpe, attributes, caller.subject), pc)
    } yield res
  }.span("createFile")

  /**
    * Create a new file with the provided id
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param entity
    *   the http FormData entity
    */
  def create(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      entity: HttpEntity
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      pc                    <- fetchContext.onCreate(projectRef)
      iri                   <- expandIri(id, pc)
      _                     <- test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, projectRef, pc)
      attributes            <- extractFileAttributes(iri, entity, storage)
      res                   <- eval(CreateFile(iri, projectRef, storageRef, storage.tpe, attributes, caller.subject), pc)
    } yield res
  }.span("createFile")

  /**
    * Create a new file linking where the id is self generated
    *
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param filename
    *   the optional filename to use
    * @param mediaType
    *   the optional media type to use
    * @param path
    *   the path where the file is located inside the storage
    */
  def createLink(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      pc  <- fetchContext.onCreate(projectRef)
      iri <- generateId(pc)
      res <- createLink(iri, projectRef, pc, storageId, filename, mediaType, path)
    } yield res
  }.span("createLink")

  /**
    * Create a new file linking it from an existing file in a storage
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param filename
    *   the optional filename to use
    * @param mediaType
    *   the optional media type to use
    * @param path
    *   the path where the file is located inside the storage
    */
  def createLink(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      pc  <- fetchContext.onCreate(projectRef)
      iri <- expandIri(id, pc)
      res <- createLink(iri, projectRef, pc, storageId, filename, mediaType, path)
    } yield res
  }.span("createLink")

  /**
    * Update an existing file
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param rev
    *   the current revision of the file
    * @param entity
    *   the http FormData entity
    */
  def update(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      rev: Int,
      entity: HttpEntity
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      pc                    <- fetchContext.onModify(projectRef)
      iri                   <- expandIri(id, pc)
      _                     <- test(UpdateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, rev, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, projectRef, pc)
      attributes            <- extractFileAttributes(iri, entity, storage)
      res                   <- eval(UpdateFile(iri, projectRef, storageRef, storage.tpe, attributes, rev, caller.subject), pc)
    } yield res
  }.span("updateFile")

  /**
    * Update a new file linking it from an existing file in a storage
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param rev
    *   the current revision of the file
    * @param filename
    *   the optional filename to use
    * @param mediaType
    *   the optional media type to use
    * @param path
    *   the path where the file is located inside the storage
    */
  def updateLink(
      id: IdSegment,
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path,
      rev: Int
  )(implicit caller: Caller): IO[FileRejection, FileResource] = {
    for {
      pc                    <- fetchContext.onModify(projectRef)
      iri                   <- expandIri(id, pc)
      _                     <- test(UpdateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, rev, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, projectRef, pc)
      resolvedFilename      <- IO.fromOption(filename.orElse(path.lastSegment), InvalidFileLink(iri))
      description           <- FileDescription(resolvedFilename, mediaType)
      attributes            <- LinkFile(storage).apply(path, description).mapError(LinkRejection(iri, storage.id, _))
      res                   <- eval(UpdateFile(iri, projectRef, storageRef, storage.tpe, attributes, rev, caller.subject), pc)
    } yield res
  }.span("updateLink")

  /**
    * Update an existing file attributes
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param projectRef
    *   the project where the file will belong
    * @param mediaType
    *   the optional media type of the file
    * @param bytes
    *   the size of the file file in bytes
    * @param digest
    *   the digest information of the file
    * @param rev
    *   the current revision of the file
    */
  def updateAttributes(
      id: IdSegment,
      projectRef: ProjectRef,
      mediaType: Option[ContentType],
      bytes: Long,
      digest: Digest,
      rev: Int
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(UpdateFileAttributes(iri, projectRef, mediaType, bytes, digest, rev, subject), pc)
    } yield res
  }.span("updateFileAttributes")

  /**
    * Update an existing file attributes
    *
    * @param iri
    *   the file iri identifier
    * @param projectRef
    *   the project where the file will belong
    */
  private[files] def updateAttributes(
      iri: Iri,
      projectRef: ProjectRef
  )(implicit subject: Subject): IO[FileRejection, FileResource] =
    for {
      file        <- fetch(iri, projectRef)
      _           <- IO.when(file.value.attributes.digest.computed)(IO.raiseError(DigestAlreadyComputed(file.id)))
      storageIdRef = IdSegmentRef(file.value.storage)
      storage     <- storages.fetch(storageIdRef, projectRef).mapError(WrappedStorageRejection)
      attr         = file.value.attributes
      newAttr     <- FetchAttributes(storage.value).apply(attr).mapError(FetchAttributesRejection(iri, storage.id, _))
      mediaType    = attr.mediaType orElse Some(newAttr.mediaType)
      res         <- updateAttributes(iri, projectRef, mediaType, newAttr.bytes, newAttr.digest, file.rev.toInt)
    } yield res

  /**
    * Add a tag to an existing file
    *
    * @param id
    *   the file identifier to expand as the iri of the storage
    * @param projectRef
    *   the project where the file belongs
    * @param tag
    *   the tag name
    * @param tagRev
    *   the tag revision
    * @param rev
    *   the current revision of the file
    */
  def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(TagFile(iri, projectRef, tagRev, tag, rev, subject), pc)
    } yield res
  }.span("tagFile")

  /**
    * Delete a tag on an existing file.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the file
    * @param projectRef
    *   the project reference where the file belongs
    * @param tag
    *   the tag name
    * @param rev
    *   the current revision of the file
    */
  def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      rev: Int
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeleteFileTag(iri, projectRef, tag, rev, subject), pc)
    } yield res
  }.span("deleteFileTag")

  /**
    * Deprecate an existing file
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param projectRef
    *   the project where the file belongs
    * @param rev
    *   the current revision of the file
    */
  def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[FileRejection, FileResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateFile(iri, projectRef, rev, subject), pc)
    } yield res
  }.span("deprecateFile")

  /**
    * Fetch the last version of a file content
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the file with its optional rev/tag
    * @param project
    *   the project where the storage belongs
    */
  def fetchContent(id: IdSegmentRef, project: ProjectRef)(implicit caller: Caller): IO[FileRejection, FileResponse] = {
    for {
      file      <- fetch(id, project)
      attributes = file.value.attributes
      storage   <- storages.fetch(file.value.storage, project)
      permission = storage.value.storageValue.readPermission
      _         <- aclCheck.authorizeForOr(project, permission)(AuthorizationFailed(project, permission))
      s         <- FetchFile(storage.value).apply(attributes).mapError(FetchRejection(file.id, storage.id, _))
      mediaType  = attributes.mediaType.getOrElse(`application/octet-stream`)
    } yield FileResponse(attributes.filename, mediaType, attributes.bytes, s)
  }.span("fetchFileContent")

  /**
    * Fetch the last version of a file
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the file with its optional rev/tag
    * @param project
    *   the project where the storage belongs
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[FileRejection, FileResource] = {
    for {
      pc      <- fetchContext.onRead(project)
      iri     <- expandIri(id.value, pc)
      notFound = FileNotFound(iri, project)
      state   <- id match {
                   case Latest(_)        => log.stateOr(project, iri, notFound)
                   case Revision(_, rev) =>
                     log.stateOr(project, iri, rev.toInt, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
                 }
    } yield state.toResource(pc.apiMappings, pc.base)
  }.span("fetchFile")

  private def createLink(
      iri: Iri,
      ref: ProjectRef,
      pc: ProjectContext,
      storageId: Option[IdSegment],
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path
  )(implicit caller: Caller): IO[FileRejection, FileResource] =
    for {
      _                     <- test(CreateFile(iri, ref, testStorageRef, testStorageType, testAttributes, caller.subject))
      (storageRef, storage) <- fetchActiveStorage(storageId, ref, pc)
      resolvedFilename      <- IO.fromOption(filename.orElse(path.lastSegment), InvalidFileLink(iri))
      description           <- FileDescription(resolvedFilename, mediaType)
      attributes            <- LinkFile(storage).apply(path, description).mapError(LinkRejection(iri, storage.id, _))
      res                   <- eval(CreateFile(iri, ref, storageRef, storage.tpe, attributes, caller.subject), pc)
    } yield res

  private def eval(cmd: FileCommand, pc: ProjectContext): IO[FileRejection, FileResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource(pc.apiMappings, pc.base))

  private def test(cmd: FileCommand) = log.dryRun(cmd.project, cmd.id, cmd)

  private def fetchActiveStorage(storageIdOpt: Option[IdSegment], ref: ProjectRef, pc: ProjectContext)(implicit
      caller: Caller
  ): IO[FileRejection, (ResourceRef.Revision, Storage)] =
    storageIdOpt match {
      case Some(storageId) =>
        for {
          iri       <- expandStorageIri(storageId, pc)
          storage   <- storages.fetch(ResourceRef(iri), ref)
          _         <- IO.when(storage.deprecated)(IO.raiseError(WrappedStorageRejection(StorageIsDeprecated(iri))))
          permission = storage.value.storageValue.writePermission
          _         <- aclCheck.authorizeForOr(ref, permission)(AuthorizationFailed(ref, permission))
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
      case None            =>
        for {
          storage   <- storages.fetchDefault(ref).mapError(WrappedStorageRejection)
          permission = storage.value.storageValue.writePermission
          _         <- aclCheck.authorizeForOr(ref, permission)(AuthorizationFailed(ref, permission))
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
    }

  private def extractFileAttributes(iri: Iri, entity: HttpEntity, storage: Storage): IO[FileRejection, FileAttributes] =
    for {
      storageAvailableSpace <- storage.storageValue.capacity.fold(UIO.none[Long]) { capacity =>
                                 storagesStatistics
                                   .get(storage.id, storage.project)
                                   .redeem(
                                     _ => Some(capacity),
                                     stat => Some(capacity - stat.spaceUsed)
                                   )
                               }
      (description, source) <- formDataExtractor(iri, entity, storage.storageValue.maxFileSize, storageAvailableSpace)
      attributes            <- SaveFile(storage).apply(description, source).mapError(SaveRejection(iri, storage.id, _))
    } yield attributes

  private def expandStorageIri(segment: IdSegment, pc: ProjectContext): IO[WrappedStorageRejection, Iri] =
    Storages.expandIri(segment, pc).mapError(WrappedStorageRejection)

  private def generateId(pc: ProjectContext)(implicit uuidF: UUIDF): UIO[Iri] =
    uuidF().map(uuid => pc.base.iri / uuid.toString)

}

object Files {

  /**
    * The file entity type.
    */
  final val entityType: EntityType = EntityType("file")

  val expandIri: ExpandIri[InvalidFileId] = new ExpandIri(InvalidFileId.apply)

  val context: ContextValue = ContextValue(contexts.files)

  /**
    * The default File API mappings
    */
  val mappings: ApiMappings = ApiMappings("file" -> fileSchema)

  type FilesLog = ScopedEventLog[Iri, FileState, FileCommand, FileEvent, FileRejection]

  private[files] def next(
      state: Option[FileState],
      event: FileEvent
  ): Option[FileState] = {
    // format: off
    def created(e: FileCreated): Option[FileState] = Option.when(state.isEmpty) {
      FileState(e.id, e.project, e.storage, e.storageType, e.attributes, Tags.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
    }

    def updated(e: FileUpdated): Option[FileState] = state.map { s =>
      s.copy(rev = e.rev, storage = e.storage, storageType = e.storageType, attributes = e.attributes, updatedAt = e.instant, updatedBy = e.subject)
    }

    def updatedAttributes(e: FileAttributesUpdated): Option[FileState] =  state.map { s =>
      s.copy(rev = e.rev, attributes = s.attributes.copy( mediaType = e.mediaType, bytes = e.bytes, digest = e.digest), updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: FileTagAdded): Option[FileState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    
    def tagDeleted(e: FileTagDeleted): Option[FileState] =  state.map { s =>
      s.copy(rev = e.rev, tags = s.tags - e.tag, updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: FileDeprecated): Option[FileState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: FileCreated           => created(e)
      case e: FileUpdated           => updated(e)
      case e: FileAttributesUpdated => updatedAttributes(e)
      case e: FileTagAdded          => tagAdded(e)
      case e: FileTagDeleted        => tagDeleted(e)
      case e: FileDeprecated        => deprecated(e)
    }
  }

  private[files] def evaluate(state: Option[FileState], cmd: FileCommand)(implicit
      clock: Clock[UIO]
  ): IO[FileRejection, FileEvent] = {

    def create(c: CreateFile) = state match {
      case None    =>
        IOUtils.instant.map(FileCreated(c.id, c.project, c.storage, c.storageType, c.attributes, 1, _, c.subject))
      case Some(_) =>
        IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateFile) = state match {
      case None                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case Some(s) if s.attributes.digest == NotComputedDigest => IO.raiseError(DigestNotComputed(c.id))
      case Some(s)                                             =>
        IOUtils.instant
          .map(FileUpdated(c.id, c.project, c.storage, c.storageType, c.attributes, s.rev + 1, _, c.subject))
    }

    def updateAttributes(c: UpdateFileAttributes) = state match {
      case None                      => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case Some(s)                   =>
        // format: off
        IOUtils.instant
          .map(FileAttributesUpdated(c.id, c.project, c.mediaType, c.bytes, c.digest, s.rev + 1, _, c.subject))
      // format: on
    }

    def tag(c: TagFile) = state match {
      case None                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0L || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                             => IOUtils.instant.map(FileTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1, _, c.subject))
    }

    def deleteTag(c: DeleteFileTag) =
      state match {
        case None                               => IO.raiseError(FileNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev          => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if !s.tags.contains(c.tag) => IO.raiseError(TagNotFound(c.tag))
        case Some(s)                            => IOUtils.instant.map(FileTagDeleted(c.id, c.project, c.tag, s.rev + 1, _, c.subject))
      }

    def deprecate(c: DeprecateFile) = state match {
      case None                      => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case Some(s)                   => IOUtils.instant.map(FileDeprecated(c.id, c.project, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateFile           => create(c)
      case c: UpdateFile           => update(c)
      case c: UpdateFileAttributes => updateAttributes(c)
      case c: TagFile              => tag(c)
      case c: DeleteFileTag        => deleteTag(c)
      case c: DeprecateFile        => deprecate(c)
    }
  }

  /**
    * Entity definition for [[Files]]
    */
  def definition(implicit clock: Clock[UIO]): EntityDefinition[Iri, FileState, FileCommand, FileEvent, FileRejection] =
    EntityDefinition(
      entityType,
      StateMachine(None, evaluate, next),
      FileEvent.serializer,
      FileState.serializer,
      Tagger[FileEvent](
        {
          case f: FileTagAdded => Some(f.tag -> f.targetRev)
          case _               => None
        },
        {
          case f: FileTagDeleted => Some(f.tag)
          case _                 => None
        }
      ),
      onUniqueViolation = (id: Iri, c: FileCommand) =>
        c match {
          case c: CreateFile => ResourceAlreadyExists(id, c.project)
          case c             => IncorrectRev(c.rev, c.rev + 1)
        }
    )

  /**
    * Constructs a Files instance
    */
  def apply(
      fetchContext: FetchContext[FileRejection],
      aclCheck: AclCheck,
      storages: Storages,
      storagesStatistics: StoragesStatistics,
      xas: Transactors,
      storageTypeConfig: StorageTypeConfig,
      config: FilesConfig
  )(implicit
      clock: Clock[UIO],
      client: HttpClient,
      uuidF: UUIDF,
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Files = {
    implicit val classicAs: ClassicActorSystem  = as.classicSystem
    implicit val sTypeConfig: StorageTypeConfig = storageTypeConfig
    new Files(
      FormDataExtractor.apply,
      ScopedEventLog(definition, config.eventLog, xas),
      aclCheck,
      fetchContext,
      storages,
      storagesStatistics
    )
  }
}
