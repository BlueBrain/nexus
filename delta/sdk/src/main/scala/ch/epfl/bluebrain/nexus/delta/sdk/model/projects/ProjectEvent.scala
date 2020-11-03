package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, Label}

/**
  * Enumeration of Project event types.
  */
sealed trait ProjectEvent extends Event {

  /**
    * @return the project ref
    */
  def ref: ProjectRef = ProjectRef(organizationLabel, label)

  /**
    * @return the project label
    */
  def label: Label

  /**
    * @return the project uuid
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
}

object ProjectEvent {

  /**
    * Evidence that a project has been created.
    *
    * @param label             the project label
    * @param uuid              the project uuid
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param rev               the project revision
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectCreated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIri,
      vocab: PrefixIri,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been updated.
    *
    * @param label             the project label
    * @param uuid              the project uuid
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
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
      apiMappings: Map[String, Iri],
      base: PrefixIri,
      vocab: PrefixIri,
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
