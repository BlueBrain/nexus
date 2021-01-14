package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef, ResourceUris, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, Lens}
import io.circe.Json

import java.time.Instant

/**
  * Enumeration of resource states.
  */

sealed trait ResourceState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status
    */
  def deprecated: Boolean

  /**
    * Converts the state into a resource representation.
    *
    * @param mappings the Api mappings to be applied in order to shorten segment ids
    * @param base     the project base to be applied in order to shorten segment ids
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[DataResource]
}

object ResourceState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial resource state.
    */
  final case object Initial extends ResourceState {

    override val deprecated: Boolean = false

    override def rev: Long = 0L

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[DataResource] = None
  }

  /**
    * A resource active state.
    *
    * @param id            the resource identifier
    * @param project       the project where the resource belongs
    * @param schemaProject the project where the schema belongs
    * @param source        the representation of the resource as posted by the subject
    * @param compacted     the compacted JSON-LD representation of the resource
    * @param expanded      the expanded JSON-LD representation of the resource
    * @param rev           the organization revision
    * @param deprecated    the deprecation status of the organization
    * @param schema        the optional schema used to constrain the resource
    * @param types         the collection of known resource types
    * @param tags          the collection of tag aliases
    * @param createdAt     the instant when the organization was created
    * @param createdBy     the identity that created the organization
    * @param updatedAt     the instant when the organization was last updated
    * @param updatedBy     the identity that last updated the organization
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      schemaProject: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Long,
      deprecated: Boolean,
      schema: ResourceRef,
      types: Set[Iri],
      tags: Map[TagLabel, Long],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ResourceState {

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[DataResource] =
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris.resource(project, schemaProject, id, schema)(mappings, base),
          rev = rev,
          types = types,
          schema = schema,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          value = Resource(id, project, tags, schema, source, compacted, expanded)
        )
      )
  }

  implicit val resourceStateRevisionLens: Lens[ResourceState, Long] = (s: ResourceState) => s.rev

}
