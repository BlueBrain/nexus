package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.{ContentType, HttpCharsets}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{Files, MediaTypeDetector}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.definition
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CancelEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileNotFound, IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.FetchStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.LinkFileAction
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship._
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.FileCopyResult.{FileCopySkipped, FileCopySuccess}
import ch.epfl.bluebrain.nexus.ship.files.FileProcessor.{forceMediaType, logger, patchMediaType}
import ch.epfl.bluebrain.nexus.ship.files.FileWiring._
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring.linkS3FileOperationOnly
import io.circe.Decoder

class FileProcessor private (
    files: Files,
    projectMapper: ProjectMapper,
    fileCopier: FileCopier,
    clock: EventClock
)(implicit mediaTypeDetector: MediaTypeDetectorConfig)
    extends EventProcessor[FileEvent] {

  override def resourceType: EntityType = Files.entityType

  override def decoder: Decoder[FileEvent] = FileEvent.serializer.codec

  override def evaluate(event: FileEvent): IO[ImportStatus] =
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result

  private def evaluateInternal(event: FileEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)
    val cRev                = event.rev - 1
    val project             = projectMapper.map(event.project)

    def getCustomMetadata(attributes: FileAttributes) = {
      val keywords = attributes.keywords
      FileCustomMetadata(
        attributes.name,
        attributes.description,
        Option.unless(keywords.isEmpty)(keywords)
      )
    }

    val fileId = FileId(event.id, project)

    event match {
      case e: FileCreated               =>
        val attrs          = e.attributes
        val newMediaType   = patchMediaType(attrs.filename, attrs.mediaType)
        val newAttrs       = e.attributes.copy(mediaType = newMediaType)
        val customMetadata = Some(getCustomMetadata(newAttrs))
        val fct            = forceMediaType(attrs.mediaType, newMediaType)
        fileCopier.copyFile(e.project, newAttrs, fct).flatMap {
          case FileCopySuccess(newPath) =>
            val linkRequest = FileLinkRequest(newPath, newMediaType, customMetadata)
            files
              .linkFile(Some(event.id), project, None, linkRequest, e.tag)
              .as(ImportStatus.Success)
          case FileCopySkipped          => IO.pure(ImportStatus.Dropped)
        }
      case e: FileUpdated               =>
        val attrs          = e.attributes
        val newMediaType   = patchMediaType(attrs.filename, attrs.mediaType)
        val newAttrs       = e.attributes.copy(mediaType = newMediaType)
        val customMetadata = Some(getCustomMetadata(newAttrs))
        val fct            = forceMediaType(attrs.mediaType, newMediaType)
        fileCopier.copyFile(e.project, newAttrs, fct).flatMap {
          case FileCopySuccess(newPath) =>
            val linkRequest = FileLinkRequest(newPath, newMediaType, customMetadata)
            files
              .updateLinkedFile(fileId, cRev, None, linkRequest, e.tag)
              .as(ImportStatus.Success)
          case FileCopySkipped          => IO.pure(ImportStatus.Dropped)
        }
      case e: FileCustomMetadataUpdated =>
        files.updateMetadata(fileId, cRev, e.metadata, e.tag).as(ImportStatus.Success)
      case e: FileAttributesUpdated     =>
        val reason = "`FileAttributesUpdated` are events related to deprecated remote storages."
        files.cancelEvent(CancelEvent(e.id, e.project, reason, cRev, e.subject)).as(ImportStatus.Success)
      case e: FileTagAdded              =>
        files.tag(fileId, e.tag, e.targetRev, cRev).as(ImportStatus.Success)
      case e: FileTagDeleted            =>
        files.deleteTag(fileId, e.tag, cRev).as(ImportStatus.Success)
      case _: FileDeprecated            =>
        files.deprecate(fileId, cRev).as(ImportStatus.Success)
      case _: FileUndeprecated          =>
        files.undeprecate(fileId, cRev).as(ImportStatus.Success)
      case _: FileCancelledEvent        => IO.pure(ImportStatus.Dropped) // Not present in the export anyway
    }
  }.recoverWith {
    case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
    case i: IncorrectRev          =>
      logger
        .warn(i)(s"An incorrect revision has been provided for '${event.id}' in project '${event.project}'")
        .as(ImportStatus.Dropped)
    case f: FileNotFound          =>
      // TODO: Remove this redemption when empty filenames are handled correctly
      logger.warn(f)(s"The file ${f.id} in project ${f.project} does not exist.").as(ImportStatus.Dropped)
    case other                    => IO.raiseError(other)
  }

}

object FileProcessor {

  private val logger = Logger[FileProcessor]

  def patchMediaType(
      filename: String,
      original: Option[ContentType]
  )(implicit mediaTypeDetector: MediaTypeDetectorConfig): Option[ContentType] =
    FileUtils
      .extension(filename)
      .flatMap(mediaTypeDetector.find)
      .map(ContentType(_, () => HttpCharsets.`UTF-8`))
      .orElse(original)

  def forceMediaType(
      originalMediaType: Option[ContentType],
      newMediaType: Option[ContentType]
  ): Boolean = originalMediaType != newMediaType

  private val noop = new EventProcessor[FileEvent] {
    override def resourceType: EntityType = Files.entityType

    override def decoder: Decoder[FileEvent] = FileEvent.serializer.codec

    override def evaluate(event: FileEvent): IO[ImportStatus] = IO.pure(ImportStatus.Dropped)
  }

  def apply(
      fetchContext: FetchContext,
      s3Client: S3StorageClient,
      projectMapper: ProjectMapper,
      rcr: ResolverContextResolution,
      config: InputConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): EventProcessor[FileEvent] = if (config.files.skipFileEvents) noop
  else {

    val storages = StorageWiring.storages(fetchContext, rcr, config, clock, xas)

    val fs         = new FetchStorage {
      override def onRead(id: ResourceRef, project: ProjectRef)(implicit caller: Caller): IO[Storage] =
        storages.flatMap(_.fetch(id, project).map(_.value))

      /**
        * Attempts to fetch the provided storage or the default one in a write context
        */
      override def onWrite(id: Option[IriOrBNode.Iri], project: ProjectRef)(implicit
          caller: Caller
      ): IO[(ResourceRef.Revision, Storage)] =
        for {
          s       <- storages
          storage <- id match {
                       case Some(id) => s.fetch(Latest(id), project)
                       case None     => s.fetchDefault(project)
                     }
        } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value
    }
    val fileCopier = FileCopier(s3Client, config.files)

    // This part is done during the patchMediaType part which also takes care of setting
    // the content type on the S3 object
    val mediaTypeDetector = new MediaTypeDetector(MediaTypeDetectorConfig.Empty)
    val linkFile          = LinkFileAction(fs, mediaTypeDetector, linkS3FileOperationOnly(s3Client))

    val files =
      new Files(
        failingFormDataExtractor,
        ScopedEventLog(definition(clock), config.eventLog, xas),
        fetchContext,
        fs,
        noFileOperations,
        linkFile
      )(FailingUUID)

    new FileProcessor(files, projectMapper, fileCopier, clock)(config.files.mediaTypeDetector)
  }

}
