package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.{BodyPartEntity, ContentType, HttpEntity, Uri}
import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => fileSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{StorageFetchRejection, StorageIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage, StorageRejection, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchAttributeRejection, FetchFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{FetchStorage, Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef, SuccessElemStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset

import java.util.UUID

/**
  * Operations for handling files
  */
final class Files(
    formDataExtractor: FormDataExtractor,
    log: FilesLog,
    aclCheck: AclCheck,
    fetchContext: FetchContext,
    storages: FetchStorage,
    storagesStatistics: StoragesStatistics,
    remoteDiskStorageClient: RemoteDiskStorageClient,
    config: StorageTypeConfig
)(implicit
    uuidF: UUIDF,
    system: ClassicActorSystem
) extends FetchFileStorage
    with FetchFileResource {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  // format: off
  private val testStorageRef = ResourceRef.Revision(iri"http://localhost/test", 1)
  private val testStorageType = StorageType.DiskStorage
  private val testAttributes = FileAttributes(UUID.randomUUID(), "http://localhost", Uri.Path.Empty, "", None, Map.empty, 0, ComputedDigest(DigestAlgorithm.default, "value"), Client)
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
    * @param tag
    *   the optional tag this file is being created with, attached to the current revision
    */
  def create(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      entity: HttpEntity,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      pc                    <- fetchContext.onCreate(projectRef)
      iri                   <- generateId(pc)
      _                     <- test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, projectRef, pc)
      metadata              <- saveFileToStorage(iri, entity, storage)
      res                   <- eval(CreateFile(iri, projectRef, storageRef, storage.tpe, metadata, caller.subject, tag))
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
    * @param tag
    *   the optional tag this file is being created with, attached to the current revision
    */
  def create(
      id: FileId,
      storageId: Option[IdSegment],
      entity: HttpEntity,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onCreate)
      _                     <- test(CreateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      metadata              <- saveFileToStorage(iri, entity, storage)
      res                   <- eval(CreateFile(iri, id.project, storageRef, storage.tpe, metadata, caller.subject, tag))
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
    * @param tag
    *   the optional tag this file link is being created with, attached to the current revision
    */
  def createLink(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      pc  <- fetchContext.onCreate(projectRef)
      iri <- generateId(pc)
      res <- createLink(iri, projectRef, pc, storageId, filename, mediaType, path, tag)
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
    * @param tag
    *   the optional tag this file link is being created with, attached to the current revision
    */
  def createLink(
      id: FileId,
      storageId: Option[IdSegment],
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc) <- id.expandIri(fetchContext.onCreate)
      res       <- createLink(iri, id.project, pc, storageId, filename, mediaType, path, tag)
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
      id: FileId,
      storageId: Option[IdSegment],
      rev: Int,
      entity: HttpEntity,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onModify)
      _                     <- test(UpdateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, rev, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      attributes            <- saveFileToStorage(iri, entity, storage)
      res                   <- eval(UpdateFile(iri, id.project, storageRef, storage.tpe, attributes, rev, caller.subject, tag))
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
      id: FileId,
      storageId: Option[IdSegment],
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path,
      rev: Int,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onModify)
      _                     <- test(UpdateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, rev, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      resolvedFilename      <- IO.fromOption(filename.orElse(path.lastSegment))(InvalidFileLink(iri))
      metadata              <- linkFile(storage, path, resolvedFilename, iri)
      res                   <- eval(
                                 UpdateFile(
                                   iri,
                                   id.project,
                                   storageRef,
                                   storage.tpe,
                                   FileAttributes.from(
                                     FileDescription(resolvedFilename, Map.empty, mediaType),
                                     metadata
                                   ),
                                   rev,
                                   caller.subject,
                                   tag
                                 )
                               )
    } yield res
  }.span("updateLink")

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
      id: FileId,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit subject: Subject): IO[FileResource] = {
    for {
      (iri, _) <- id.expandIri(fetchContext.onModify)
      res      <- eval(TagFile(iri, id.project, tagRev, tag, rev, subject))
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
      id: FileId,
      tag: UserTag,
      rev: Int
  )(implicit subject: Subject): IO[FileResource] = {
    for {
      (iri, _) <- id.expandIri(fetchContext.onModify)
      res      <- eval(DeleteFileTag(iri, id.project, tag, rev, subject))
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
      id: FileId,
      rev: Int
  )(implicit subject: Subject): IO[FileResource] = {
    for {
      (iri, _) <- id.expandIri(fetchContext.onModify)
      res      <- eval(DeprecateFile(iri, id.project, rev, subject))
    } yield res
  }.span("deprecateFile")

  /**
    * Undeprecate an existing file
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param projectRef
    *   the project where the file belongs
    * @param rev
    *   the current revision of the file
    */
  def undeprecate(
      id: FileId,
      rev: Int
  )(implicit subject: Subject): IO[FileResource] = {
    for {
      (iri, _) <- id.expandIri(fetchContext.onModify)
      res      <- eval(UndeprecateFile(iri, id.project, rev, subject))
    } yield res
  }.span("undeprecateFile")

  /**
    * Fetch the last version of a file content
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the file with its optional rev/tag
    * @param project
    *   the project where the storage belongs
    */
  def fetchContent(id: FileId)(implicit caller: Caller): IO[FileResponse] = {
    for {
      file      <- fetch(id)
      attributes = file.value.attributes
      storage   <- storages.fetch(file.value.storage, id.project)
      _         <- validateAuth(id.project, storage.value.storageValue.readPermission)
      s          = fetchFile(storage.value, attributes, file.id)
      mediaType  = attributes.mediaType.getOrElse(`application/octet-stream`)
    } yield FileResponse(attributes.filename, mediaType, attributes.bytes, s.attemptNarrow[FileRejection])
  }.span("fetchFileContent")

  private def fetchFile(storage: Storage, attr: FileAttributes, fileId: Iri): IO[AkkaSource] =
    FetchFile(storage, remoteDiskStorageClient, config)
      .apply(attr)
      .adaptError { case e: FetchFileRejection =>
        FetchRejection(fileId, storage.id, e)
      }

  override def fetch(id: FileId): IO[FileResource] =
    (for {
      (iri, _) <- id.expandIri(fetchContext.onRead)
      state    <- fetchState(id, iri)
    } yield state.toResource).span("fetchFile")

  private def fetchState(id: FileId, iri: Iri): IO[FileState] = {
    val notFound = FileNotFound(iri, id.project)
    id.id match {
      case Latest(_)        => log.stateOr(id.project, iri, notFound)
      case Revision(_, rev) => log.stateOr(id.project, iri, rev, notFound, RevisionNotFound)
      case Tag(_, tag)      => log.stateOr(id.project, iri, tag, notFound, TagNotFound(tag))
    }
  }

  private def createLink(
      iri: Iri,
      ref: ProjectRef,
      pc: ProjectContext,
      storageId: Option[IdSegment],
      filename: Option[String],
      mediaType: Option[ContentType],
      path: Uri.Path,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] =
    for {
      _                     <- test(CreateFile(iri, ref, testStorageRef, testStorageType, testAttributes, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, ref, pc)
      resolvedFilename      <- IO.fromOption(filename.orElse(path.lastSegment))(InvalidFileLink(iri))
      fileMetadata          <- linkFile(storage, path, resolvedFilename, iri)
      res                   <- eval(
                                 CreateFile(
                                   iri,
                                   ref,
                                   storageRef,
                                   storage.tpe,
                                   FileAttributes
                                     .from(FileDescription(resolvedFilename, Map.empty, mediaType), fileMetadata),
                                   caller.subject,
                                   tag
                                 )
                               )
    } yield res

  private def linkFile(
      storage: Storage,
      path: Uri.Path,
      filename: String,
      fileId: Iri
  ): IO[FileStorageMetadata]                           =
    LinkFile(storage, remoteDiskStorageClient, config)
      .apply(path, filename)
      .adaptError { case e: StorageFileRejection => LinkRejection(fileId, storage.id, e) }

  private def eval(cmd: FileCommand): IO[FileResource] = FilesLog.eval(log)(cmd)

  private def test(cmd: FileCommand) = log.dryRun(cmd.project, cmd.id, cmd)

  override def fetchAndValidateActiveStorage(storageIdOpt: Option[IdSegment], ref: ProjectRef, pc: ProjectContext)(
      implicit caller: Caller
  ): IO[(ResourceRef.Revision, Storage)] =
    storageIdOpt match {
      case Some(storageId) =>
        for {
          iri     <- expandStorageIri(storageId, pc)
          storage <- storages.fetch(ResourceRef(iri), ref)
          _       <- IO.whenA(storage.deprecated)(IO.raiseError(WrappedStorageRejection(StorageIsDeprecated(iri))))
          _       <- validateAuth(ref, storage.value.storageValue.writePermission)
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
      case None            =>
        for {
          storage <- storages.fetchDefault(ref).adaptError { case e: StorageRejection =>
                       WrappedStorageRejection(e)
                     }
          _       <- validateAuth(ref, storage.value.storageValue.writePermission)
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
    }

  private def validateAuth(project: ProjectRef, permission: Permission)(implicit c: Caller): IO[Unit] =
    aclCheck.authorizeForOr(project, permission)(AuthorizationFailed(project, permission))

  private def saveFileToStorage(
      iri: Iri,
      entity: HttpEntity,
      storage: Storage
  ): IO[FileAttributes] =
    for {
      info                <- extractFormData(iri, storage, entity)
      userSuppliedMetadata = FileDescription.from(info)
      fileMetadata        <- saveFile(iri, storage, userSuppliedMetadata, info.contents)
    } yield FileAttributes.from(userSuppliedMetadata, fileMetadata)

  private def extractFormData(iri: Iri, storage: Storage, entity: HttpEntity): IO[UploadedFileInformation] =
    for {
      storageAvailableSpace <- storagesStatistics.getStorageAvailableSpace(storage)
      fi                    <- formDataExtractor(iri, entity, storage.storageValue.maxFileSize, storageAvailableSpace)
    } yield fi

  private def saveFile(
      iri: Iri,
      storage: Storage,
      metadata: FileDescription,
      source: BodyPartEntity
  ): IO[FileStorageMetadata]                                                    =
    SaveFile(storage, remoteDiskStorageClient, config)
      .apply(metadata.filename, source)
      .adaptError { case e: SaveFileRejection => SaveRejection(iri, storage.id, e) }

  private def expandStorageIri(segment: IdSegment, pc: ProjectContext): IO[Iri] =
    Storages.expandIri(segment, pc).adaptError { case s: StorageRejection =>
      WrappedStorageRejection(s)
    }

  private def generateId(pc: ProjectContext): IO[Iri] =
    uuidF().map(uuid => pc.base.iri / uuid.toString)

  def states(offset: Offset): SuccessElemStream[FileState] = log.states(Scope.root, offset)

  private[files] def updateAttributes(iri: Iri, project: ProjectRef): IO[Unit] =
    for {
      f       <- log.stateOr(project, iri, FileNotFound(iri, project))
      storage <- storages.fetch(IdSegmentRef(f.storage), f.project).map(_.value).adaptError {
                   case e: StorageFetchRejection => WrappedStorageRejection(e)
                 }
      _       <- updateAttributes(f: FileState, storage: Storage)
    } yield ()

  private[files] def updateAttributes(f: FileState, storage: Storage): IO[Unit] = {
    val attr = f.attributes
    for {
      _        <- IO.raiseWhen(f.attributes.digest.computed)(DigestAlreadyComputed(f.id))
      newAttr  <- fetchAttributes(storage, attr, f.id)
      mediaType = attr.mediaType orElse Some(newAttr.mediaType)
      command   = UpdateFileAttributes(f.id, f.project, mediaType, newAttr.bytes, newAttr.digest, f.rev, f.updatedBy)
      _        <- log.evaluate(f.project, f.id, command)
    } yield ()
  }

  private def fetchAttributes(storage: Storage, attr: FileAttributes, fileId: Iri): IO[ComputedFileAttributes] =
    FetchAttributes(storage, remoteDiskStorageClient)
      .apply(attr)
      .adaptError { case e: FetchAttributeRejection => FetchAttributesRejection(fileId, storage.id, e) }

}

object Files {

  /**
    * The file entity type.
    */
  final val entityType: EntityType = EntityType("file")

  val context: ContextValue = ContextValue(contexts.files)

  /**
    * The default File API mappings
    */
  val mappings: ApiMappings = ApiMappings("file" -> fileSchema)

  type FilesLog = ScopedEventLog[Iri, FileState, FileCommand, FileEvent, FileRejection]

  object FilesLog {
    def eval(log: FilesLog)(cmd: FileCommand): IO[FileResource] =
      log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)
  }

  private[files] def next(
      state: Option[FileState],
      event: FileEvent
  ): Option[FileState] = {
    // format: off
    def created(e: FileCreated): Option[FileState] = Option.when(state.isEmpty) {
      FileState(e.id, e.project, e.storage, e.storageType, e.attributes, Tags(e.tag, e.rev), e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
    }

    def updated(e: FileUpdated): Option[FileState] = state.map { s =>
      s.copy(rev = e.rev, storage = e.storage, storageType = e.storageType, attributes = e.attributes, tags = s.tags ++ Tags(e.tag, e.rev), updatedAt = e.instant, updatedBy = e.subject)
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

    def undeprecated(e: FileUndeprecated): Option[FileState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = false, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: FileCreated           => created(e)
      case e: FileUpdated           => updated(e)
      case e: FileAttributesUpdated => updatedAttributes(e)
      case e: FileTagAdded          => tagAdded(e)
      case e: FileTagDeleted        => tagDeleted(e)
      case e: FileDeprecated        => deprecated(e)
      case e: FileUndeprecated      => undeprecated(e)
    }
  }

  private[files] def evaluate(clock: Clock[IO])(state: Option[FileState], cmd: FileCommand): IO[FileEvent] = {

    def create(c: CreateFile) = state match {
      case None    =>
        clock.realTimeInstant.map(
          FileCreated(c.id, c.project, c.storage, c.storageType, c.attributes, 1, _, c.subject, c.tag)
        )
      case Some(_) =>
        IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateFile) = state match {
      case None                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case Some(s) if s.attributes.digest == NotComputedDigest => IO.raiseError(DigestNotComputed(c.id))
      case Some(s)                                             =>
        clock.realTimeInstant
          .map(FileUpdated(c.id, c.project, c.storage, c.storageType, c.attributes, s.rev + 1, _, c.subject, c.tag))
    }

    def updateAttributes(c: UpdateFileAttributes) = state match {
      case None                                    => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev               => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated                 => IO.raiseError(FileIsDeprecated(c.id))
      case Some(s) if s.attributes.digest.computed => IO.raiseError(DigestAlreadyComputed(s.id))
      case Some(s) if !c.digest.computed           => IO.raiseError(DigestNotComputed(s.id))
      case Some(s)                                 =>
        // format: off
        clock.realTimeInstant
          .map(FileAttributesUpdated(c.id, c.project, s.storage, s.storageType, c.mediaType, c.bytes, c.digest, s.rev + 1, _, c.subject))
      // format: on
    }

    def tag(c: TagFile) = state match {
      case None                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0L || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                             =>
        clock.realTimeInstant.map(
          FileTagAdded(c.id, c.project, s.storage, s.storageType, c.targetRev, c.tag, s.rev + 1, _, c.subject)
        )
    }

    def deleteTag(c: DeleteFileTag) =
      state match {
        case None                               => IO.raiseError(FileNotFound(c.id, c.project))
        case Some(s) if s.rev != c.rev          => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case Some(s) if !s.tags.contains(c.tag) => IO.raiseError(TagNotFound(c.tag))
        case Some(s)                            =>
          clock.realTimeInstant.map(
            FileTagDeleted(c.id, c.project, s.storage, s.storageType, c.tag, s.rev + 1, _, c.subject)
          )
      }

    def deprecate(c: DeprecateFile) = state match {
      case None                      => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(FileDeprecated(c.id, c.project, s.storage, s.storageType, s.rev + 1, _, c.subject))
    }

    def undeprecate(c: UndeprecateFile) = state match {
      case None                      => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if !s.deprecated  => IO.raiseError(FileIsNotDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(FileUndeprecated(c.id, c.project, s.storage, s.storageType, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateFile           => create(c)
      case c: UpdateFile           => update(c)
      case c: UpdateFileAttributes => updateAttributes(c)
      case c: TagFile              => tag(c)
      case c: DeleteFileTag        => deleteTag(c)
      case c: DeprecateFile        => deprecate(c)
      case c: UndeprecateFile      => undeprecate(c)
    }
  }

  /**
    * Entity definition for [[Files]]
    */
  def definition(
      clock: Clock[IO]
  ): ScopedEntityDefinition[Iri, FileState, FileCommand, FileEvent, FileRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(clock)(_, _), next),
      FileEvent.serializer,
      FileState.serializer,
      Tagger[FileEvent](
        {
          case f: FileCreated  => f.tag.map(t => t -> f.rev)
          case f: FileUpdated  => f.tag.map(t => t -> f.rev)
          case f: FileTagAdded => Some(f.tag -> f.targetRev)
          case _               => None
        },
        {
          case f: FileTagDeleted => Some(f.tag)
          case _                 => None
        }
      ),
      _ => None,
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
      fetchContext: FetchContext,
      aclCheck: AclCheck,
      storages: FetchStorage,
      storagesStatistics: StoragesStatistics,
      xas: Transactors,
      storageTypeConfig: StorageTypeConfig,
      config: FilesConfig,
      remoteDiskStorageClient: RemoteDiskStorageClient,
      clock: Clock[IO]
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing]
  ): Files = {
    implicit val classicAs: ClassicActorSystem = as.classicSystem
    new Files(
      FormDataExtractor(config.mediaTypeDetector),
      ScopedEventLog(definition(clock), config.eventLog, xas),
      aclCheck,
      fetchContext,
      storages,
      storagesStatistics,
      remoteDiskStorageClient,
      storageTypeConfig
    )
  }
}
