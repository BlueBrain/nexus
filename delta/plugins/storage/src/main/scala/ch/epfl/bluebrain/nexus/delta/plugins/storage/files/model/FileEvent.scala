package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRef
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRef.RemoteDiskStorageRef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, Label, TagLabel}

import java.time.Instant

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
    * @param id         the file identifier
    * @param project    the project the file belongs to
    * @param storage    the reference to the used storage
    * @param attributes the file attributes
    * @param instant    the instant this event was created
    * @param subject    the subject which created this event
    */
  final case class FileCreated(
      id: Iri,
      project: ProjectRef,
      storage: StorageRef,
      attributes: FileAttributes,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends FileEvent

  /**
    * Event for the modification of an existing file
    *
    * @param id         the file identifier
    * @param project    the project the file belongs to
    * @param storage    the reference to the remote storage used
    * @param attributes the file attributes
    * @param rev        the last known revision of the file
    * @param instant    the instant this event was created
    * @param subject    the subject which created this event
    */
  final case class FileUpdated(
      id: Iri,
      project: ProjectRef,
      storage: StorageRef,
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
    * @param storage   the reference to the remote storage used
    * @param mediaType the media type of the file
    * @param bytes     the size of the file file in bytes
    * @param digest    the digest information of the file
    * @param rev       the last known revision of the file
    * @param instant   the instant this event was created
    * @param subject   the identity associated to this event
    */
  final case class FileComputedAttributesUpdated(
      id: Iri,
      project: ProjectRef,
      storage: RemoteDiskStorageRef,
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
}
