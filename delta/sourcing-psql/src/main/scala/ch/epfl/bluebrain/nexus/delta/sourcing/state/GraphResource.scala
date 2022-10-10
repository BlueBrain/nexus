package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import io.circe.Json

/**
  * Common representation of a scoped resource as [[Graph].
    *
  * @param tpe
  *   the resource type
  * @param project
  *   the parent project
  * @param id
  *   the resource id
  * @param rev
  *   the resource revision
  * @param deprecated
  *   the resource deprecation status
  * @param schema
  *   a reference to the schema that constrains the resource
  * @param types
  *   the collection of resource types
  * @param graph
  *   a graph representation of the resource
  * @param metadataGraph
  *   the metadata graph for the resource
  * @param source
  *   the original json representation of the resource
  */
final case class GraphResource(tpe: EntityType,
                                project: ProjectRef,
                                id: Iri,
                                rev: Int,
                                deprecated: Boolean,
                                schema: ResourceRef,
                                types: Set[Iri],
                                graph: Graph,
                                metadataGraph: Graph,
                                source: Json)
