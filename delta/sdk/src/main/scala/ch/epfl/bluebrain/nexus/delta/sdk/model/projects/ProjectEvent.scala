package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import org.apache.jena.iri.IRI

/**
  * Enumeration of Project event types.
  */
sealed trait ProjectEvent extends Product with Serializable {

  /**
    * @return the project label
    */
  def label: Label

  /**
    * @return the project unique identifier
    */
  def uuid: UUID

  /**
    * @return the parent organization label
    */
  def organizationLabel: Label

  /**
    * @return the parent organization unique identifier
    */
  def organizationUuid: UUID

  /**
    * @return the revision number that this event generates
    */
  def rev: Long

  /**
    * @return the timestamp associated to this event
    */
  def instant: Instant

  /**
    * @return the identity associated to this event
    */
  def subject: Subject
}

object ProjectEvent {

  /**
    * Evidence that a project has been created.
    *
    * @param label             the project label
    * @param uuid              the project unique identifier
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectCreated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      description: Option[String],
      apiMappings: Map[String, IRI],
      base: IRI,
      vocab: IRI,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent {

    /**
      *  the revision number that this event generates
      */
    val rev: Long = 1L
  }

  /**
    * Evidence that a project has been updated.
    *
    * @param label             the project label
    * @param uuid              the project unique identifier
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param rev               the revision number that this event generates
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectUpdated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      description: Option[String],
      apiMappings: Map[String, IRI],
      base: IRI,
      vocab: IRI,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been deprecated.
    *
    * @param label             the label (segment) of the project
    * @param uuid              the permanent identifier for the project
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param rev               the revision number that this event generates
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectDeprecated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent
}
