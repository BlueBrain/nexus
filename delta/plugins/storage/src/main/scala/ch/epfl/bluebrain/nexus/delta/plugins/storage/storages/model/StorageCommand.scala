package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json

/**
  * Enumeration of Storage command types.
  */
sealed trait StorageCommand extends Product with Serializable {

  /**
    * @return
    *   the project where the storage belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the storage identifier
    */
  def id: Iri

  /**
    * the last known revision of the storage
    * @return
    */
  def rev: Int

  /**
    * @return
    *   the identity associated to this command
    */
  def subject: Subject
}

object StorageCommand {

  /**
    * Command to create a new storage
    *
    * @param id
    *   the storage identifier
    * @param project
    *   the project the storage belongs to
    * @param fields
    *   additional fields to configure the storage
    * @param source
    *   the representation of the storage as posted by the subject
    * @param subject
    *   the identity associated to this command
    */
  final case class CreateStorage(
      id: Iri,
      project: ProjectRef,
      fields: StorageFields,
      source: Json,
      subject: Subject
  ) extends StorageCommand {
    override def rev: Int = 0
  }

  /**
    * Command to update an existing storage
    *
    * @param id
    *   the storage identifier
    * @param project
    *   the project the storage belongs to
    * @param fields
    *   additional fields to configure the storage
    * @param source
    *   the representation of the storage as posted by the subject
    * @param rev
    *   the last known revision of the storage
    * @param subject
    *   the identity associated to this command
    */
  final case class UpdateStorage(
      id: Iri,
      project: ProjectRef,
      fields: StorageFields,
      source: Json,
      rev: Int,
      subject: Subject
  ) extends StorageCommand

  /**
    * Command to tag a storage
    *
    * @param id
    *   the storage identifier
    * @param project
    *   the project the storage belongs to
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''tagRev''
    * @param rev
    *   the last known revision of the storage
    * @param subject
    *   the identity associated to this command
    */
  final case class TagStorage(id: Iri, project: ProjectRef, targetRev: Int, tag: UserTag, rev: Int, subject: Subject)
      extends StorageCommand

  /**
    * Command to deprecate a storage
    *
    * @param id
    *   the storage identifier
    * @param project
    *   the project the storage belongs to
    * @param rev
    *   the last known revision of the storage
    * @param subject
    *   the identity associated to this command
    */
  final case class DeprecateStorage(id: Iri, project: ProjectRef, rev: Int, subject: Subject) extends StorageCommand

}
