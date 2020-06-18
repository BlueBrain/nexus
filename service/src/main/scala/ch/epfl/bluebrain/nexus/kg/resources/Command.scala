package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant

import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import io.circe.Json

/**
  * Enumeration of resource command types.
  */
sealed trait Command extends Product with Serializable {

  /**
    * @return the resource identifier
    */
  def id: Id[ProjectRef]

  /**
    * @return the last known revision of the resource when this command was created
    */
  def rev: Long

  /**
    * @return the instant when this command was created
    */
  def instant: Instant

  /**
    * @return the identity which created this command
    */
  def subject: Subject
}

object Command {

  /**
    * An intent for resource creation.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param schema       the schema that is used to constrain the resource
    * @param types        the collection of known resource types (asserted or inferred)
    * @param source       the source representation of the resource
    * @param instant      the instant when this command was created
    * @param subject      the identity which created this command
    */
  final case class Create(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      schema: Ref,
      types: Set[AbsoluteIri],
      source: Json,
      instant: Instant,
      subject: Subject
  ) extends Command {

    /**
      * the initial command revision
      */
    val rev: Long = 0L
  }

  /**
    * An intent for resource update.
    *
    * @param id           the resource identifier
    * @param schema       the schema that is used to constrain the resource
    * @param rev          the last known revision of the resource when this command was created
    * @param types        the collection of known resource types (asserted or inferred)
    * @param source       the new source representation of the resource
    * @param instant      the instant when this command was created
    * @param subject      the identity which created this command
    */
  final case class Update(
      id: Id[ProjectRef],
      schema: Ref,
      rev: Long,
      types: Set[AbsoluteIri],
      source: Json,
      instant: Instant,
      subject: Subject
  ) extends Command

  /**
    * An intent for resource deprecation.
    *
    * @param id      the resource identifier
    * @param schema  the schema that is used to constrain the resource
    * @param rev     the last known revision of the resource when this command was created
    * @param instant the instant when this command was created
    */
  final case class Deprecate(
      id: Id[ProjectRef],
      schema: Ref,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends Command

  /**
    * An intent to add a tag to a resource (revision aliasing).
    *
    * @param id        the resource identifier
    * @param schema    the schema that is used to constrain the resource
    * @param rev       the last known revision of the resource when this command was created
    * @param targetRev the revision to be tagged with the provided ''tag''
    * @param tag       the tag's name
    * @param instant   the instant when this command was created
    */
  final case class AddTag(
      id: Id[ProjectRef],
      schema: Ref,
      rev: Long,
      targetRev: Long,
      tag: String,
      instant: Instant,
      subject: Subject
  ) extends Command

  /**
    * An intent to create a file resource.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage where the file is going to be saved
    * @param value        the file metadata
    * @param instant      the instant when this event was recorded
    * @param subject      the subject which generated this event
    */
  final case class CreateFile(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      storage: StorageReference,
      value: FileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Command {

    /**
      * the initial command revision
      */
    val rev: Long = 0L

    /**
      * the schema that is used to constrain the resource
      */
    val schema: Ref = fileSchemaUri.ref

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  /**
    * An intent to update a file digest.
    *
    * @param id      the resource identifier
    * @param storage the reference to the storage that is computing the file digest
    * @param rev     the last known revision of the resource when this command was created
    * @param value   the file digest
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class UpdateFileDigest(
      id: Id[ProjectRef],
      storage: StorageReference,
      rev: Long,
      value: Digest,
      instant: Instant,
      subject: Subject
  ) extends Command {

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  /**
    * An intent to update a file attributes.
    *
    * @param id      the resource identifier
    * @param storage the reference to the storage that is computing the file attributes
    * @param rev     the last known revision of the resource when this command was created
    * @param value   the file attributes
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class UpdateFileAttributes(
      id: Id[ProjectRef],
      storage: StorageReference,
      rev: Long,
      value: StorageFileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Command {

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  /**
    * An intent to update a file resource.
    *
    * @param id      the resource identifier
    * @param storage the reference to the storage where the file is going to be saved
    * @param rev     the last known revision of the resource when this command was created
    * @param value   the file metadata
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class UpdateFile(
      id: Id[ProjectRef],
      storage: StorageReference,
      rev: Long,
      value: FileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Command {

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

}
