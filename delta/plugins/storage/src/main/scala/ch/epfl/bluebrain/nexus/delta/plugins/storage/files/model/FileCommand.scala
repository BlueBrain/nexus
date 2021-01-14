package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Enumeration of File command types.
  */
sealed trait FileCommand extends Product with Serializable {

  /**
    * @return the project where the file belongs to
    */
  def project: ProjectRef

  /**
    * @return the file identifier
    */
  def id: Iri

  /**
    * @return the identity associated to this command
    */
  def subject: Subject
}

object FileCommand {

  /**
    * Command to create a new file
    *
    * @param id          the file identifier
    * @param project     the project the file belongs to
    * @param storage     the reference to the used storage
    * @param storageType the type of storage
    * @param attributes  the file attributes
    * @param subject     the identity associated to this command
    */
  final case class CreateFile(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      subject: Subject
  ) extends FileCommand

  /**
    * Command to update an existing file
    *
    * @param id          the file identifier
    * @param project     the project the file belongs to
    * @param storage     the reference to the used storage
    * @param storageType the type of storage
    * @param attributes  the file attributes
    * @param subject     the identity associated to this command
    */
  final case class UpdateFile(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      rev: Long,
      subject: Subject
  ) extends FileCommand

  /**
    * Command to update an asynchronously computed file attributes.
    * This command gets issued when linking a file using a ''RemoteDiskStorage''.
    * Since the attributes cannot be computed synchronously, ''NotComputedDigest'' and wrong size are returned
    *
    * @param id         the file identifier
    * @param project    the project the file belongs to
    * @param mediaType  the media type of the file
    * @param bytes      the size of the file file in bytes
    * @param digest     the digest information of the file
    * @param subject    the identity associated to this command
    */
  final case class UpdateFileAttributes(
      id: Iri,
      project: ProjectRef,
      mediaType: ContentType,
      bytes: Long,
      digest: Digest,
      rev: Long,
      subject: Subject
  ) extends FileCommand

  /**
    * Command to tag a file
    *
    * @param id        the file identifier
    * @param project   the project the file belongs to
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the file
    * @param subject   the identity associated to this command
    */
  final case class TagFile(id: Iri, project: ProjectRef, targetRev: Long, tag: TagLabel, rev: Long, subject: Subject)
      extends FileCommand

  /**
    * Command to deprecate a file
    *
    * @param id      the file identifier
    * @param project the project the file belongs to
    * @param rev     the last known revision of the file
    * @param subject the identity associated to this command
    */
  final case class DeprecateFile(id: Iri, project: ProjectRef, rev: Long, subject: Subject) extends FileCommand

}
