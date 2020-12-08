package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Lens, ProjectResource}

/**
  * Enumeration of Project state types.
  */
sealed trait ProjectState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status
    */
  def deprecated: Boolean

  /**
    * @return the schema reference that projects conforms to
    */
  final def schema: ResourceRef = Latest(schemas.projects)

  /**
    * @return the collection of known types of projects resources
    */
  final def types: Set[Iri] = Set(nxv.Project)

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[ProjectResource]
}

object ProjectState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial state.
    */
  final case object Initial extends ProjectState {

    /**
      * @return the current state revision
      */
    override val rev: Long = 0L

    /**
      * @return the current deprecation status
      */
    override val deprecated: Boolean = false

    /**
      * Converts the state into a resource representation.
      */
    override val toResource: Option[ProjectResource] = None
  }

  /**
    * State used for all resources that have been created and later possibly updated or deprecated.
    *
    * @param label             the project label
    * @param uuid              the project unique identifier
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param rev               the current state revision
    * @param deprecated        the current state deprecation status
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base Iri for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param createdAt         the instant when the resource was created
    * @param createdBy         the subject that created the resource
    * @param updatedAt         the instant when the resource was last updated
    * @param updatedBy         the subject that last updated the resource
    */
  final case class Current(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      deprecated: Boolean,
      description: Option[String],
      apiMappings: ApiMappings,
      base: ProjectBase,
      vocab: Iri,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ProjectState {

    private val projectRef = ProjectRef(organizationLabel, label)

    private val uris = ResourceUris.project(projectRef)

    /**
      * @return the project information
      */
    def project: Project =
      Project(
        label = label,
        uuid = uuid,
        organizationLabel = organizationLabel,
        organizationUuid = organizationUuid,
        description = description,
        apiMappings = apiMappings,
        base = base,
        vocab = vocab
      )

    /**
      * Converts the state into a resource representation.
      */
    override def toResource: Option[ProjectResource] = {
      Some(
        ResourceF(
          id = uris.relativeAccessUri.toIri,
          uris = uris,
          rev = rev,
          types = types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = project
        )
      )
    }
  }

  implicit val revisionLens: Lens[ProjectState, Long] = (s: ProjectState) => s.rev

}
