package ch.epfl.bluebrain.nexus.migration.v1_4.events.admin

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.PrefixIri
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent

import java.time.Instant
import java.util.UUID
import scala.util.matching.Regex

sealed trait ProjectEvent extends ToMigrateEvent {

  /**
    * @return the permanent identifier for the project
    */
  def id: UUID

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

  private val regex: Regex = "[^a-zA-Z0-9_-]".r

  /**
    * Evidence that a project has been created.
    *
    * @param id                the permanent identifier for the project
    * @param label             the label (segment) of the project
    * @param organizationUuid  the permanent identifier for the parent organization
    * @param organizationLabel the parent organization label
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectCreated(
      id: UUID,
      label: String,
      organizationUuid: UUID,
      organizationLabel: Label,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIri,
      vocab: PrefixIri,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent {

    def parsedLabel: Label = Label(label)
      .orElse(Label(regex.replaceAllIn(label, "-")))
      .getOrElse(
        throw new IllegalArgumentException(s"Could not fix project label $label")
      )

    /**
      * the revision number that this event generates
      */
    val rev: Long = 1L
  }

  /**
    * Evidence that a project has been updated.
    *
    * @param id          the permanent identifier for the project
    * @param label       the label (segment) of the project
    * @param description an optional project description
    * @param apiMappings the API mappings
    * @param base        the base IRI for generated resource IDs
    * @param vocab       an optional vocabulary for resources with no context
    * @param rev         the revision number that this event generates
    * @param instant     the timestamp associated to this event
    * @param subject     the identity associated to this event
    */
  final case class ProjectUpdated(
      id: UUID,
      label: String,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIri,
      vocab: PrefixIri,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been deprecated.
    *
    * @param id      the permanent identifier for the project
    * @param rev     the revision number that this event generates
    * @param instant the timestamp associated to this event
    * @param subject the identity associated to this event
    */
  final case class ProjectDeprecated(
      id: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

}
