package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts, nxvFile, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{resourcesSelector, SseEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, ResourceRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of File event types.
  */
sealed trait FileEvent extends ScopedEvent {

  /**
    * @return
    *   the file identifier
    */
  def id: Iri

  /**
    * @return
    *   the project where the file belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the reference to the used storage
    */
  def storage: ResourceRef.Revision

  /**
    * @return
    *   the type of storage
    */
  def storageType: StorageType

}

object FileEvent {

  /**
    * Event for the creation of a file
    *
    * @param id
    *   the file identifier
    * @param project
    *   the project the file belongs to
    * @param storage
    *   the reference to the used storage
    * @param storageType
    *   the type of storage
    * @param attributes
    *   the file attributes
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class FileCreated(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the modification of an existing file
    *
    * @param id
    *   the file identifier
    * @param project
    *   the project the file belongs to
    * @param storage
    *   the reference to the remote storage used
    * @param storageType
    *   the type of storage
    * @param attributes
    *   the file attributes
    * @param rev
    *   the last known revision of the file
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class FileUpdated(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the modification of an asynchronously computed file attributes. This event gets recorded when linking a
    * file using a ''RemoteDiskStorage''. Since the attributes cannot be computed synchronously, ''NotComputedDigest''
    * and wrong size are returned
    *
    * @param id
    *   the file identifier
    * @param project
    *   the project the file belongs to
    * @param storage
    *   the reference to the remote storage used
    * @param storageType
    *   the type of storage
    * @param mediaType
    *   the optional media type of the file
    * @param bytes
    *   the size of the file file in bytes
    * @param digest
    *   the digest information of the file
    * @param rev
    *   the last known revision of the file
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the identity associated to this event
    */
  final case class FileAttributesUpdated(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      mediaType: Option[ContentType],
      bytes: Long,
      digest: Digest,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for to tag a file
    *
    * @param id
    *   the file identifier
    * @param project
    *   the project the file belongs to
    * @param storage
    *   the reference to the used storage
    * @param storageType
    *   the type of storage
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''tagRev''
    * @param rev
    *   the last known revision of the file
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject creating this event
    */
  final case class FileTagAdded(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for to delete a tag from a file
    *
    * @param id
    *   the file identifier
    * @param project
    *   the project the file belongs to
    * @param storage
    *   the reference to the used storage
    * @param storageType
    *   the type of storage
    * @param tag
    *   the tag that was deleted
    * @param rev
    *   the last known revision of the file
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject creating this event
    */
  final case class FileTagDeleted(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the deprecation of a file
    * @param id
    *   the file identifier
    * @param project
    *   the project the file belongs to
    * @param storage
    *   the reference to the used storage
    * @param storageType
    *   the type of storage
    * @param rev
    *   the last known revision of the file
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject creating this event
    */
  final case class FileDeprecated(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, FileEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                        = Serializer.circeConfiguration
    implicit val digestCodec: Codec.AsObject[Digest]                 =
      deriveConfiguredCodec[Digest]
    implicit val fileAttributesCodec: Codec.AsObject[FileAttributes] =
      deriveConfiguredCodec[FileAttributes]
    implicit val coder: Codec.AsObject[FileEvent]                    = deriveConfiguredCodec[FileEvent]
    Serializer(_.id)
  }

  val fileEventMetricEncoder: ScopedEventMetricEncoder[FileEvent] =
    new ScopedEventMetricEncoder[FileEvent] {
      override def databaseDecoder: Decoder[FileEvent] = serializer.codec

      override def entityType: EntityType = Files.entityType

      override def eventToMetric: FileEvent => ProjectScopedMetric = event =>
        ProjectScopedMetric.from(
          event,
          event match {
            case _: FileCreated           => Created
            case _: FileUpdated           => Updated
            case _: FileAttributesUpdated => Updated
            case _: FileTagAdded          => Tagged
            case _: FileTagDeleted        => TagDeleted
            case _: FileDeprecated        => Deprecated
          },
          event.id,
          Set(nxvFile),
          FileExtraFields.fromEvent(event).asJsonObject
        )
    }

  def sseEncoder(implicit base: BaseUri, @nowarn("cat=unused") config: StorageTypeConfig): SseEncoder[FileEvent] =
    new SseEncoder[FileEvent] {
      override val databaseDecoder: Decoder[FileEvent] = serializer.codec

      override def entityType: EntityType = Files.entityType

      override val selectors: Set[Label] = Set(Label.unsafe("files"), resourcesSelector)

      @nowarn("cat=unused")
      override val sseEncoder: Encoder.AsObject[FileEvent] = {
        val context                                         = ContextValue(Vocabulary.contexts.metadata, contexts.files)
        val metadataKeys: Set[String]                       =
          Set("subject", "types", "source", "project", "rev", "instant", "digest", "mediaType", "attributes", "bytes")
        implicit val circeConfig: Configuration             = Configuration.default
          .withDiscriminator(keywords.tpe)
          .copy(transformMemberNames = {
            case "id"                                  => "_fileId"
            case "subject"                             => nxv.eventSubject.prefix
            case field if metadataKeys.contains(field) => s"_$field"
            case other                                 => other
          })
        implicit val subjectEncoder: Encoder[Subject]       = IriEncoder.jsonEncoder[Subject]
        implicit val projectRefEncoder: Encoder[ProjectRef] = IriEncoder.jsonEncoder[ProjectRef]

        Encoder.encodeJsonObject.contramapObject { event =>
          val storageAndType                    = event match {
            case created: FileCreated => Some(created.storage -> created.storageType)
            case updated: FileUpdated => Some(updated.storage -> updated.storageType)
            case _                    => None
          }
          implicit val storageType: StorageType = storageAndType.map(_._2).getOrElse(StorageType.DiskStorage)

          val storageJsonOpt = storageAndType.map { case (storage, tpe) =>
            Json.obj(
              keywords.id           -> storage.iri.asJson,
              keywords.tpe          -> tpe.toString.asJson,
              nxv.rev.prefix        -> storage.rev.asJson,
              nxv.resourceId.prefix -> storage.iri.asJson
            )
          }
          deriveConfiguredEncoder[FileEvent]
            .encodeObject(event)
            .remove("storage")
            .remove("storageType")
            .addIfExists("_storage", storageJsonOpt)
            .add(nxv.resourceId.prefix, event.id.asJson)
            .add(keywords.context, context.value)
        }
      }
    }

  /**
    * @param storage
    *   the iri of the storage
    * @param storageType
    *   the storage type
    * @param newFileWritten
    *   indicates that a new (physical) file has been written
    * @param bytes
    *   the size of the file
    * @param mediaType
    *   the media type of the file
    * @param origin
    *   the file's origin
    */
  final private case class FileExtraFields(
      storage: Iri,
      storageType: StorageType,
      newFileWritten: Option[Int],
      bytes: Option[Long],
      mediaType: Option[ContentType],
      origin: Option[FileAttributesOrigin]
  )

  private object FileExtraFields {
    def fromEvent(event: FileEvent): FileExtraFields =
      event match {
        case c: FileCreated if c.attributes.digest.computed =>
          FileExtraFields(
            c.storage.iri,
            c.storageType,
            Some(1),
            Some(c.attributes.bytes),
            c.attributes.mediaType,
            Some(c.attributes.origin)
          )
        case c: FileCreated                                 =>
          FileExtraFields(
            c.storage.iri,
            c.storageType,
            Some(1),
            None,
            None,
            Some(c.attributes.origin)
          )
        case u: FileUpdated if u.attributes.digest.computed =>
          FileExtraFields(
            u.storage.iri,
            u.storageType,
            Some(1),
            Some(u.attributes.bytes),
            u.attributes.mediaType,
            Some(u.attributes.origin)
          )
        case u: FileUpdated                                 =>
          FileExtraFields(
            u.storage.iri,
            u.storageType,
            Some(1),
            None,
            None,
            Some(u.attributes.origin)
          )
        case fau: FileAttributesUpdated                     =>
          FileExtraFields(
            fau.storage.iri,
            fau.storageType,
            None,
            Some(fau.bytes),
            fau.mediaType,
            Some(FileAttributesOrigin.Storage)
          )
        case fta: FileTagAdded                              =>
          FileExtraFields(fta.storage.iri, fta.storageType, None, None, None, None)
        case ftd: FileTagDeleted                            =>
          FileExtraFields(ftd.storage.iri, ftd.storageType, None, None, None, None)
        case fd: FileDeprecated                             =>
          FileExtraFields(fd.storage.iri, fd.storageType, None, None, None, None)
      }

    implicit val fileExtraFieldsEncoder: Encoder.AsObject[FileExtraFields] =
      deriveEncoder[FileExtraFields]
  }
}
