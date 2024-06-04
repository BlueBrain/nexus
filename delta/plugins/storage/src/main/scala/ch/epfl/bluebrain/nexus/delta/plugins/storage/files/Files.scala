package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.{ContentType, Uri}
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
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes.DelegationResponse
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => fileSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{StorageFetchRejection, StorageIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage, StorageRejection, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchAttributeRejection, FetchFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{FetchStorage, Storages}
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
    fileOperations: FileOperations
)(implicit uuidF: UUIDF)
    extends FetchFileStorage
    with FetchFileResource {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  // format: off
  private val testStorageRef = ResourceRef.Revision(iri"http://localhost/test", 1)
  private val testStorageType = StorageType.DiskStorage
  private val testAttributes = FileAttributes(UUID.randomUUID(), "http://localhost", Uri.Path.Empty, "", None, Map.empty, None, None, 0, ComputedDigest(DigestAlgorithm.default, "value"), Client)
  // format: on

  /**
    * Create a new file where the id is self generated
    *
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param projectRef
    *   the project where the file will belong
    * @param uploadRequest
    *   the upload request containing the form data entity
    * @param tag
    *   the optional tag this file is being created with, attached to the current revision
    */
  def create(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      uploadRequest: FileUploadRequest,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      pc                    <- fetchContext.onCreate(projectRef)
      iri                   <- generateId(pc)
      _                     <- test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, projectRef, pc)
      attributes            <- saveFileToStorage(iri, storage, uploadRequest)
      res                   <- eval(CreateFile(iri, projectRef, storageRef, storage.tpe, attributes, caller.subject, tag))
    } yield res
  }.span("createFile")

  /**
    * Create a new file with the provided id
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param uploadRequest
    *   the upload request containing the form data entity
    * @param tag
    *   the optional tag this file is being created with, attached to the current revision
    */
  def create(
      id: FileId,
      storageId: Option[IdSegment],
      uploadRequest: FileUploadRequest,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onCreate)
      _                     <- test(CreateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      metadata              <- saveFileToStorage(iri, storage, uploadRequest)
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
    * @param path
    *   the path where the file is located inside the storage
    * @param tag
    *   the optional tag this file link is being created with, attached to the current revision
    */
  def createLink(
      storageId: Option[IdSegment],
      projectRef: ProjectRef,
      description: FileDescription,
      path: Uri.Path,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      pc  <- fetchContext.onCreate(projectRef)
      iri <- generateId(pc)
      res <- createLink(iri, projectRef, pc, storageId, description, path, tag)
    } yield res
  }.span("createLink")

  def delegate(projectRef: ProjectRef, description: FileDescription, storageId: Option[IdSegment])(implicit
      caller: Caller
  ): IO[DelegationResponse] = {
    for {
      pc           <- fetchContext.onCreate(projectRef)
      iri          <- generateId(pc)
      _            <-
        test(CreateFile(iri, projectRef, testStorageRef, testStorageType, testAttributes, caller.subject, tag = None))
      (_, storage) <- fetchAndValidateActiveStorage(storageId, projectRef, pc)
      metadata     <- fileOperations.delegate(storage, description.filename)
    } yield DelegationResponse(metadata.bucket, iri, metadata.path, description.metadata)
  }.span("delegate")

  /**
    * Create a new file linking it from an existing file in a storage
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param path
    *   the path where the file is located inside the storage
    * @param tag
    *   the optional tag this file link is being created with, attached to the current revision
    */
  def createLink(
      id: FileId,
      storageId: Option[IdSegment],
      description: FileDescription,
      path: Uri.Path,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc) <- id.expandIri(fetchContext.onCreate)
      res       <- createLink(iri, id.project, pc, storageId, description, path, tag)
    } yield res
  }.span("createLink")

  /**
    * Update an existing file
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param rev
    *   the current revision of the file
    * @param uploadRequest
    *   the http FormData entity
    * @param tag
    *   the optional tag this file link is being updated with, attached to the current revision
    */
  def update(
      id: FileId,
      storageId: Option[IdSegment],
      rev: Int,
      uploadRequest: FileUploadRequest,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onModify)
      _                     <- test(UpdateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, rev, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      attributes            <- saveFileToStorage(iri, storage, uploadRequest)
      res                   <- eval(UpdateFile(iri, id.project, storageRef, storage.tpe, attributes, rev, caller.subject, tag))
    } yield res
  }.span("updateFile")

  def updateMetadata(
      id: FileId,
      rev: Int,
      metadata: FileCustomMetadata,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, _) <- id.expandIri(fetchContext.onModify)
      res      <- eval(UpdateFileCustomMetadata(iri, id.project, metadata, rev, caller.subject, tag))
    } yield res
  }.span("updateFileMetadata")

  def registerFile(
      id: FileId,
      storageId: Option[IdSegment],
      metadata: Option[FileCustomMetadata],
      path: Uri.Path,
      tag: Option[UserTag],
      mediaType: Option[ContentType]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onCreate)
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      s3Metadata            <- fileOperations.register(storage, path)
      filename              <- IO.fromOption(path.lastSegment)(InvalidFilePath)
      attr                   = FileAttributes.from(
                                 FileDescription(filename, mediaType.orElse(s3Metadata.contentType), metadata),
                                 s3Metadata.metadata
                               )
      res                   <- eval(
                                 CreateFile(
                                   iri,
                                   id.project,
                                   storageRef,
                                   storage.tpe,
                                   attr,
                                   caller.subject,
                                   tag
                                 )
                               )
    } yield res
  }.span("registerFile")

  def updateRegisteredFile(
      id: FileId,
      storageId: Option[IdSegment],
      metadata: Option[FileCustomMetadata],
      rev: Int,
      path: Uri.Path,
      tag: Option[UserTag],
      mediaType: Option[ContentType]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onModify)
      _                     <- test(UpdateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, rev, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      s3Metadata            <- fileOperations.register(storage, path)
      filename              <- IO.fromOption(path.lastSegment)(InvalidFilePath)
      attr                   = FileAttributes.from(
                                 FileDescription(filename, mediaType.orElse(s3Metadata.contentType), metadata),
                                 s3Metadata.metadata
                               )
      res                   <- eval(
                                 UpdateFile(
                                   iri,
                                   id.project,
                                   storageRef,
                                   storage.tpe,
                                   attr,
                                   rev,
                                   caller.subject,
                                   tag
                                 )
                               )
    } yield res
  }.span("updateRegisteredFile")

  /**
    * Update a new file linking it from an existing file in a storage
    *
    * @param id
    *   the file identifier to expand as the iri of the file
    * @param storageId
    *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
    * @param rev
    *   the current revision of the file
    * @param path
    *   the path where the file is located inside the storage
    */
  def updateLink(
      id: FileId,
      storageId: Option[IdSegment],
      description: FileDescription,
      path: Uri.Path,
      rev: Int,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] = {
    for {
      (iri, pc)             <- id.expandIri(fetchContext.onModify)
      _                     <- test(UpdateFile(iri, id.project, testStorageRef, testStorageType, testAttributes, rev, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, id.project, pc)
      metadata              <- linkFile(storage, path, description.filename, iri)
      res                   <- eval(
                                 UpdateFile(
                                   iri,
                                   id.project,
                                   storageRef,
                                   storage.tpe,
                                   FileAttributes.from(
                                     description,
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
    fileOperations.fetch(storage, attr).adaptError { case e: FetchFileRejection =>
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
      description: FileDescription,
      path: Uri.Path,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[FileResource] =
    for {
      _                     <- test(CreateFile(iri, ref, testStorageRef, testStorageType, testAttributes, caller.subject, tag))
      (storageRef, storage) <- fetchAndValidateActiveStorage(storageId, ref, pc)
      storageMetadata       <- linkFile(storage, path, description.filename, iri)
      res                   <- eval(
                                 CreateFile(
                                   iri,
                                   ref,
                                   storageRef,
                                   storage.tpe,
                                   FileAttributes
                                     .from(
                                       description,
                                       storageMetadata
                                     ),
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
  ): IO[FileStorageMetadata] =
    fileOperations.link(storage, path, filename).adaptError { case e: StorageFileRejection =>
      LinkRejection(fileId, storage.id, e)
    }

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
          storage <- fetchDefaultStorage(ref)
          _       <- validateAuth(ref, storage.value.storageValue.writePermission)
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
    }

  private def fetchDefaultStorage(ref: ProjectRef) = storages.fetchDefault(ref).adaptError { case e: StorageRejection =>
    WrappedStorageRejection(e)
  }

  private def validateAuth(project: ProjectRef, permission: Permission)(implicit c: Caller): IO[Unit] =
    aclCheck.authorizeForOr(project, permission)(AuthorizationFailed(project, permission))

  private def saveFileToStorage(iri: Iri, storage: Storage, uploadRequest: FileUploadRequest): IO[FileAttributes] = {
    for {
      info            <- formDataExtractor(iri, uploadRequest.entity, storage.storageValue.maxFileSize)
      description      = FileDescription.from(info, uploadRequest.metadata)
      storageMetadata <- fileOperations.save(storage, info, uploadRequest.contentLength)
    } yield FileAttributes.from(description, storageMetadata)
  }.adaptError { case e: SaveFileRejection => SaveRejection(iri, storage.id, e) }

  private def expandStorageIri(segment: IdSegment, pc: ProjectContext): IO[Iri]                                   =
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

  private def fetchAttributes(storage: Storage, attr: FileAttributes, fileId: Iri): IO[ComputedFileAttributes] = {
    fileOperations
      .fetchAttributes(storage, attr)
      .adaptError { case e: FetchAttributeRejection => FetchAttributesRejection(fileId, storage.id, e) }
  }

  def cancelEvent(command: CancelEvent): IO[Unit] = log.evaluate(command.project, command.id, command).void

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
    
    def updatedCustomMetadata(e: FileCustomMetadataUpdated): Option[FileState] = state.map { s =>
      val newAttributes = FileAttributes.setCustomMetadata(s.attributes, e.metadata)
      val newTags = s.tags ++ Tags(e.tag, e.rev)
      s.copy(rev = e.rev, attributes = newAttributes, tags = newTags, updatedAt = e.instant, updatedBy = e.subject)
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

    def cancelEvent(e: FileCancelledEvent): Option[FileState] = state.map { s =>
      s.copy(rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: FileCreated               => created(e)
      case e: FileUpdated               => updated(e)
      case e: FileAttributesUpdated     => updatedAttributes(e)
      case e: FileCustomMetadataUpdated => updatedCustomMetadata(e)
      case e: FileTagAdded              => tagAdded(e)
      case e: FileTagDeleted            => tagDeleted(e)
      case e: FileDeprecated            => deprecated(e)
      case e: FileUndeprecated          => undeprecated(e)
      case e: FileCancelledEvent        => cancelEvent(e)
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

    def updateCustomMetadata(c: UpdateFileCustomMetadata) = state match {
      case None                      => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s)                   =>
        clock.realTimeInstant
          .map(
            FileCustomMetadataUpdated(
              c.id,
              c.project,
              s.storage,
              s.storageType,
              c.metadata,
              s.rev + 1,
              _,
              c.subject,
              c.tag
            )
          )
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

    def cancelEvent(c: CancelEvent) = state match {
      case None                      => IO.raiseError(FileNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s)                   =>
        clock.realTimeInstant.map(
          FileCancelledEvent(c.id, c.project, s.storage, s.storageType, c.reason, s.rev + 1, _, c.subject)
        )
    }

    cmd match {
      case c: CreateFile               => create(c)
      case c: UpdateFile               => update(c)
      case c: UpdateFileAttributes     => updateAttributes(c)
      case c: UpdateFileCustomMetadata => updateCustomMetadata(c)
      case c: TagFile                  => tag(c)
      case c: DeleteFileTag            => deleteTag(c)
      case c: DeprecateFile            => deprecate(c)
      case c: UndeprecateFile          => undeprecate(c)
      case c: CancelEvent              => cancelEvent(c)
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
          case f: FileCreated               => f.tag.map(t => t -> f.rev)
          case f: FileUpdated               => f.tag.map(t => t -> f.rev)
          case f: FileTagAdded              => Some(f.tag -> f.targetRev)
          case f: FileCustomMetadataUpdated => f.tag.map(t => t -> f.rev)
          case _                            => None
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
      xas: Transactors,
      config: FilesConfig,
      fileOps: FileOperations,
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
      fileOps
    )
  }
}
