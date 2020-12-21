package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

import java.time.Instant

/**
  * Enumeration of Storage event types.
  */
sealed trait StorageEvent extends Event {

  /**
    * @return the storage identifier
    */
  def id: Iri

  /**
    * @return the project where the storage belongs to
    */
  def project: ProjectRef

}

object StorageEvent {

  /**
    * Event for the creation of a storage
    *
    * @param id      the storage identifier
    * @param project the project the storage belongs to
    * @param value   additional fields to configure the storage
    * @param instant the instant this event was created
    * @param subject the subject which created this event
    */
  final case class StorageCreated(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Secret[Json],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends StorageEvent

  /**
    * Event for the modification of an existing storage
    *
    * @param id        the storage identifier
    * @param project   the project the storage belongs to
    * @param value     additional fields to configure the storage
    * @param rev       the last known revision of the storage
    * @param instant   the instant this event was created
    * @param subject   the subject which created this event
    */
  final case class StorageUpdated(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Secret[Json],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends StorageEvent

  /**
    * Event for to tag a storage
    *
    * @param id        the storage identifier
    * @param project   the project the storage belongs to
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the storage
    * @param instant   the instant this event was created
    * @param subject   the subject creating this event
    */
  final case class StorageTagAdded(
                                    id: Iri,
                                    project: ProjectRef,
                                    targetRev: Long,
                                    tag: TagLabel,
                                    rev: Long,
                                    instant: Instant,
                                    subject: Subject
  ) extends StorageEvent

  /**
    * Event for the deprecation of a storage
    * @param id      the storage identifier
    * @param project the project the storage belongs to
    * @param rev     the last known revision of the storage
    * @param instant the instant this event was created
    * @param subject the subject creating this event
    */
  final case class StorageDeprecated(id: Iri, project: ProjectRef, rev: Long, instant: Instant, subject: Subject)
      extends StorageEvent
}
