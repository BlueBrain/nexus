package ch.epfl.bluebrain.nexus.admin.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

sealed trait ProjectState extends Product with Serializable

object ProjectState {

  /**
    * Initial state for all resources.
    */
  final case object Initial extends ProjectState

  /**
    * State used for all resources that have been created and later possibly updated or deprecated.
    *
    * @param id           the permanent identifier for the project
    * @param label        the label (segment) of the resource
    * @param organizationUuid  the permanent identifier for the parent organization
    * @param organizationLabel the parent organization label
    * @param description  an optional project description
    * @param apiMappings  the API mappings
    * @param base         the base IRI for generated resource IDs
    * @param vocab        an optional vocabulary for resources with no context
    * @param rev          the selected revision number
    * @param instant      the timestamp associated with this state
    * @param subject      the identity associated with this state
    * @param deprecated   the deprecation status
    */
  final case class Current(
      id: UUID,
      label: String,
      organizationUuid: UUID,
      organizationLabel: String,
      description: Option[String],
      apiMappings: Map[String, AbsoluteIri],
      base: AbsoluteIri,
      vocab: AbsoluteIri,
      rev: Long,
      instant: Instant,
      subject: Subject,
      deprecated: Boolean
  ) extends ProjectState

}
