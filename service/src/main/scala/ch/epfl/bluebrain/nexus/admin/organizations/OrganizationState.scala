package ch.epfl.bluebrain.nexus.admin.organizations

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.admin.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.admin.types.{ResourceF, ResourceMetadata}
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject

/**
  * Enumeration of organization states.
  */
sealed trait OrganizationState extends Product with Serializable

object OrganizationState {

  sealed trait Initial extends OrganizationState

  /**
    * The initial (undefined) state.
    */
  final case object Initial extends Initial

  /**
    * Initial organization state.
    *
    * @param id           the permanent identifier of the organization
    * @param rev          the organization revision
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param deprecated   the deprecation status of the organization
    * @param createdAt    the instant when the organization was created
    * @param createdBy    the identity that created the organization
    * @param updatedAt    the instant when the organization was last updated
    * @param updatedBy    the identity that last updated the organization
    */
  final case class Current(
      id: UUID,
      rev: Long,
      label: String,
      description: Option[String],
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends OrganizationState {

    /**
      * Convert the state into [[ResourceF]].
      *
      * @param   http implicitly available [[HttpConfig]]
      * @return [[Organization]] wrapped in [[ResourceF]]
      */
    def toResource(implicit http: HttpConfig): OrganizationResource =
      ResourceF(
        http.orgsBaseIri + label,
        id,
        rev,
        deprecated,
        Set(nxv.Organization.value),
        createdAt,
        createdBy,
        updatedAt,
        updatedBy,
        Organization(label, description)
      )

    /**
      * Convert the state into [[ResourceMetadata]]
      *
      * @param   http implicitly available [[HttpConfig]]
      * @return [[ResourceMetadata]] for the [[Organization]]
      */
    def toResourceMetadata(implicit http: HttpConfig): ResourceMetadata =
      ResourceF.unit(
        http.orgsBaseIri + label,
        id,
        rev,
        deprecated,
        Set(nxv.Organization.value),
        createdAt,
        createdBy,
        updatedAt,
        updatedBy
      )
  }

}
