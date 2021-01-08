package ch.epfl.bluebrain.nexus.migration.v1_4.events.kg

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent
import io.circe.Json

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of resource event types.
  */
sealed trait Event extends ToMigrateEvent {

  def project: UUID

  /**
    * @return the resource identifier
    */
  def id: Iri

  /**
    * @return the organization resource identifier
    */
  def organization: UUID

  /**
    * @return the revision that this event generated
    */
  def rev: Long

  /**
    * @return the instant when this event was recorded
    */
  def instant: Instant

  /**
    * @return the subject which created this event
    */
  def subject: Subject
}

object Event {

  /**
    * A witness to a resource creation.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param schema       the schema that was used to constrain the resource
    * @param types        the collection of known resource types
    * @param source       the source representation of the resource
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class Created(
      id: Iri,
      project: UUID,
      organization: UUID,
      schema: ResourceRef,
      types: Set[Iri],
      source: Json,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the revision that this event generated
      */
    val rev: Long = 1L
  }

  /**
    * A witness to a resource update.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the revision that this event generated
    * @param types        the collection of new known resource types
    * @param source       the source representation of the new resource value
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class Updated(
      id: Iri,
      project: UUID,
      organization: UUID,
      rev: Long,
      types: Set[Iri],
      source: Json,
      instant: Instant,
      subject: Subject
  ) extends Event

  /**
    * A witness to a resource deprecation.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the revision that this event generated
    * @param types        the collection of new known resource types
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class Deprecated(
      id: Iri,
      project: UUID,
      organization: UUID,
      rev: Long,
      types: Set[Iri],
      instant: Instant,
      subject: Subject
  ) extends Event

  /**
    * A witness to a resource tagging. This event creates an alias for a revision.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the revision that this event generated
    * @param targetRev    the revision that is being aliased with the provided ''tag''
    * @param tag          the tag of the alias for the provided ''rev''
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class TagAdded(
      id: Iri,
      project: UUID,
      organization: UUID,
      rev: Long,
      targetRev: Long,
      tag: TagLabel,
      instant: Instant,
      subject: Subject
  ) extends Event

  /**
    * A witness that a file resource has been created.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to save the file
    * @param attributes   the metadata of the file
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileCreated(
      id: Iri,
      project: UUID,
      organization: UUID,
      storage: StorageReference,
      attributes: FileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the revision that this event generated
      */
    val rev: Long = 1L

    /**
      * the collection of known resource types
      */
    val types: Set[Iri] = Set.empty
  }

  /**
    * A witness that a file digest has been updated.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to fetch the digest of the file
    * @param rev          the revision that this event generated
    * @param digest       the updated digest
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileDigestUpdated(
      id: Iri,
      project: UUID,
      organization: UUID,
      storage: StorageReference,
      rev: Long,
      digest: Digest,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the collection of known resource types
      */
    val types: Set[Iri] = Set.empty
  }

  /**
    * A witness that a file attributes has been updated.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to fetch the attributes of the file
    * @param rev          the revision that this event generated
    * @param attributes   the updated file attributes
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileAttributesUpdated(
      id: Iri,
      project: UUID,
      organization: UUID,
      storage: StorageReference,
      rev: Long,
      attributes: StorageFileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the collection of known resource types
      */
    val types: Set[Iri] = Set.empty
  }

  /**
    * A witness that a file resource has been updated.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to save the file
    * @param rev          the revision that this event generated
    * @param attributes   the metadata of the file
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileUpdated(
      id: Iri,
      project: UUID,
      organization: UUID,
      storage: StorageReference,
      rev: Long,
      attributes: FileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the collection of known resource types
      */
    val types: Set[Iri] = Set.empty
  }
}
