package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, ResourceRef, TagLabel}
import io.circe.{Encoder, Json}
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of File event types.
  */
sealed trait FileEvent extends Event {

  /**
    * @return the file identifier
    */
  def id: Iri

  /**
    * @return the project where the file belongs to
    */
  def project: ProjectRef

}

object FileEvent {

  /**
    * Event for the creation of a file
    *
    * @param id          the file identifier
    * @param project     the project the file belongs to
    * @param storage     the reference to the used storage
    * @param storageType the type of storage
    * @param attributes  the file attributes
    * @param instant     the instant this event was created
    * @param subject     the subject which created this event
    */
  final case class FileCreated(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the modification of an existing file
    *
    * @param id          the file identifier
    * @param project     the project the file belongs to
    * @param storage     the reference to the remote storage used
    * @param storageType the type of storage
    * @param attributes  the file attributes
    * @param rev         the last known revision of the file
    * @param instant     the instant this event was created
    * @param subject     the subject which created this event
    */
  final case class FileUpdated(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the modification of an asynchronously computed file attributes.
    * This event gets recorded when linking a file using a ''RemoteDiskStorage''.
    * Since the attributes cannot be computed synchronously, ''NotComputedDigest'' and wrong size are returned
    *
    * @param id        the file identifier
    * @param project   the project the file belongs to
    * @param mediaType the media type of the file
    * @param bytes     the size of the file file in bytes
    * @param digest    the digest information of the file
    * @param rev       the last known revision of the file
    * @param instant   the instant this event was created
    * @param subject   the identity associated to this event
    */
  final case class FileAttributesUpdated(
      id: Iri,
      project: ProjectRef,
      mediaType: ContentType,
      bytes: Long,
      digest: Digest,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for to tag a file
    *
    * @param id        the file identifier
    * @param project   the project the file belongs to
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the file
    * @param instant   the instant this event was created
    * @param subject   the subject creating this event
    */
  final case class FileTagAdded(
      id: Iri,
      project: ProjectRef,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the deprecation of a file
    * @param id      the file identifier
    * @param project the project the file belongs to
    * @param rev     the last known revision of the file
    * @param instant the instant this event was created
    * @param subject the subject creating this event
    */
  final case class FileDeprecated(id: Iri, project: ProjectRef, rev: Long, instant: Instant, subject: Subject)
      extends FileEvent

  private val context = ContextValue(Vocabulary.contexts.metadata, contexts.files)

  private val metadataKeys: Set[String] =
    Set("subject", "types", "source", "project", "rev", "instant", "digest", "mediaType", "attributes", "bytes")

  @nowarn("cat=unused")
  implicit private val circeConfig: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "id"                                  => "fileId"
      case "subject"                             => nxv.eventSubject.prefix
      case field if metadataKeys.contains(field) => s"_$field"
      case other                                 => other
    })

  @nowarn("cat=unused")
  implicit def fileEventJsonLdEncoder(implicit
      baseUri: BaseUri,
      config: StorageTypeConfig
  ): JsonLdEncoder[FileEvent] = {
    implicit val subjectEncoder: Encoder[Subject] = Identity.subjectIdEncoder

    implicit val encoder: Encoder.AsObject[FileEvent] = Encoder.encodeJsonObject.contramapObject { event =>
      val storageAndType       = event match {
        case created: FileCreated => Some(created.storage -> created.storageType)
        case updated: FileUpdated => Some(updated.storage -> updated.storageType)
        case _                    => None
      }
      implicit val storageType = storageAndType.map(_._2).getOrElse(StorageType.DiskStorage)

      val storageJsonOpt = storageAndType.map { case (storage, tpe) =>
        Json.obj(
          keywords.id  -> storage.iri.asJson,
          keywords.tpe -> tpe.iri.asJson,
          "_rev"       -> storage.rev.asJson
        )
      }
      deriveConfiguredEncoder[FileEvent]
        .encodeObject(event)
        .remove("storage")
        .remove("storageType")
        .addIfExists("_storage", storageJsonOpt)
    }

    JsonLdEncoder.computeFromCirce[FileEvent](context)
  }
}
