package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

/**
  * A schema representation
  *
  * @param id        the resource identifier
  * @param project   the project where the resource belongs
  * @param source    the representation of the resource as posted by the subject
  * @param compacted the compacted JSON-LD representation of the schema
  * @param graph     the Graph  representation of the schema
  */
//TODO: Review this when dealing with schemas
final case class Schema(id: Iri, project: ProjectRef, source: Json, compacted: CompactedJsonLd, graph: Graph)
