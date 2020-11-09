package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, ResourceF, ResourceRef}
import io.circe.Json

/**
  * Enumeration of resource states.
  */

sealed trait ResourceState extends Product with Serializable {

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[DataResource]
}

object ResourceState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial resources state.
    */
  final case object Initial extends ResourceState {
    override val toResource: Option[DataResource] = None
  }

  /**
    * Initial resources state.
    *
    * @param id         the resource identifier
    * @param project    the project where the resource belongs
    * @param source     the representation of the resource as posted by the subject
    * @param compacted  the compacted JSON-LD representation of the resource
    * @param expanded   the expanded JSON-LD representation of the resource
    * @param rev        the organization revision
    * @param deprecated the deprecation status of the organization
    * @param schema     the optional schema used to constrain the resource
    * @param types      the collection of known resource types
    * @param tags       the collection of tag aliases
    * @param createdAt  the instant when the organization was created
    * @param createdBy  the identity that created the organization
    * @param updatedAt  the instant when the organization was last updated
    * @param updatedBy  the identity that last updated the organization
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Long,
      deprecated: Boolean,
      schema: ResourceRef,
      types: Set[Iri],
      tags: Map[String, Long],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ResourceState {

    override def toResource: Option[DataResource] =
      Some(
        ResourceF(
          id = _ => id,
          accessUrl = AccessUrl.resource(project, id, schema)(_),
          rev = rev,
          types = types,
          schema = schema,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          value = Resource(id, project, schema, source, compacted, expanded)
        )
      )
  }

}
