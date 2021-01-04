package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef, ResourceUris, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{Lens, SchemaResource}
import io.circe.Json

import java.time.Instant

/**
  * Enumeration of schema states.
  */

sealed trait SchemaState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status
    */
  def deprecated: Boolean

  /**
    * @return the schema reference that schemas conforms to
    */
  final def schema: ResourceRef = Latest(schemas.shacl)

  /**
    * @return the collection of known types of schema resources
    */
  final def types: Set[Iri] = Set(nxv.Schema)

  /**
    * Converts the state into a schema representation.
    *
    * @param mappings the Api mappings to be applied in order to shorten segment ids
    * @param base     the project base to be applied in order to shorten segment ids
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[SchemaResource]
}

object SchemaState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial schema state.
    */
  final case object Initial extends SchemaState {

    override val deprecated: Boolean = false

    override def rev: Long = 0L

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[SchemaResource] = None
  }

  /**
    * A schema active state.
    *
    * @param id         the schema identifier
    * @param project    the project where the schema belongs
    * @param source     the representation of the schema as posted by the subject
    * @param compacted  the compacted JSON-LD representation of the schema
    * @param expanded   the expanded JSON-LD representation of the schema with the imports resolutions applied
    * @param graph      the RDF Graph representation of the schema
    * @param ontologies the RDF Graph representation of the schema ontologies
    * @param rev        the organization revision
    * @param deprecated the deprecation status of the organization
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
      graph: Graph,
      ontologies: Graph,
      rev: Long,
      deprecated: Boolean,
      tags: Map[TagLabel, Long],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends SchemaState {

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[SchemaResource] =
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris.schema(project, id)(mappings, base),
          rev = rev,
          types = types,
          schema = schema,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          value = Schema(id, project, tags, source, compacted, expanded, graph, ontologies)
        )
      )
  }

  implicit val schemaStateRevisionLens: Lens[SchemaState, Long] = (s: SchemaState) => s.rev

}
