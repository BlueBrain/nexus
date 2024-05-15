package ch.epfl.bluebrain.nexus.ship.files

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.definition
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.CancelEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileNotFound, IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileCustomMetadata, FileEvent, FileId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{FetchStorage, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship._
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring.alwaysAuthorize
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.files.FileProcessor.logger
import ch.epfl.bluebrain.nexus.ship.files.FileWiring._
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring
import io.circe.Decoder
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

class FileProcessor private (
    files: Files,
    projectMapper: ProjectMapper,
    fileCopier: FileCopier,
    clock: EventClock
) extends EventProcessor[FileEvent] {

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

    // TODO: Remove the 5_000_000_000L limit when the multipart works correctly
    event match {
      case e: FileCreated               =>
        val attrs          = e.attributes
        val customMetadata = Some(getCustomMetadata(attrs))
        IO.whenA(attrs.bytes < 5_000_000_000L) {
          fileCopier.copyFile(e.project, attrs).flatMap { newPath =>
            files.registerFile(fileId, None, customMetadata, newPath, e.tag, attrs.mediaType).void
          }
        }
      case e: FileUpdated               =>
        val attrs          = e.attributes
        val customMetadata = Some(getCustomMetadata(attrs))
        IO.whenA(attrs.bytes < 5_000_000_000L) {
          fileCopier.copyFile(e.project, attrs).flatMap { newPath =>
            files.updateRegisteredFile(fileId, None, customMetadata, cRev, newPath, e.tag, attrs.mediaType).void
          }
        }
      case e: FileCustomMetadataUpdated =>
        files.updateMetadata(fileId, cRev, e.metadata, e.tag)
      case e: FileAttributesUpdated     =>
        val reason = "`FileAttributesUpdated` are events related to deprecated remote storages."
        files.cancelEvent(CancelEvent(e.id, e.project, reason, cRev, e.subject))
      case e: FileTagAdded              =>
        files.tag(fileId, e.tag, e.targetRev, cRev)
      case e: FileTagDeleted            =>
        files.deleteTag(fileId, e.tag, cRev)
      case _: FileDeprecated            =>
        files.deprecate(fileId, cRev)
      case _: FileUndeprecated          =>
        files.undeprecate(fileId, cRev)
      case _: FileCancelledEvent        => IO.unit // Not present in the export anyway
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision has been provided").as(ImportStatus.Dropped)
      case f: FileNotFound          =>
        // TODO: Remove this redemption when empty filenames are handled correctly
        logger.warn(f)(s"The file ${f.id} in project ${f.project} does not exist.").as(ImportStatus.Dropped)
      case n: NoSuchKeyException    =>
        event match {
          // format: off
          case e: FileCreated => logger.error(n)(s"The file ${e.id} in project ${e.project} at path ${e.attributes.path} does not exist in the source bucket. ").as(ImportStatus.Dropped)
          case e: FileUpdated => logger.error(n)(s"The file ${e.id} in project ${e.project} at path ${e.attributes.path} does not exist in the source bucket. ").as(ImportStatus.Dropped)
          case e              => logger.error(n)(s"This error should not occur as event for file ${e.id} at rev ${e.rev} is not moving any file.").as(ImportStatus.Dropped)
          // format: on
        }
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

}

object FileProcessor {

  private val logger = Logger[FileProcessor]

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

    val fe         = new FetchStorage {
      override def fetch(id: IdSegmentRef, project: ProjectRef): IO[StorageResource] =
        storages.flatMap(_.fetch(id, project))

      override def fetchDefault(project: ProjectRef): IO[StorageResource] =
        storages.flatMap(_.fetchDefault(project))
    }
    val fileCopier = FileCopier(s3Client, config.files)

    val files =
      new Files(
        failingFormDataExtractor,
        ScopedEventLog(definition(clock), config.eventLog, xas),
        alwaysAuthorize,
        fetchContext,
        fe,
        registerOperationOnly(s3Client)
      )(FailingUUID)

    new FileProcessor(files, projectMapper, fileCopier, clock)
  }

}
