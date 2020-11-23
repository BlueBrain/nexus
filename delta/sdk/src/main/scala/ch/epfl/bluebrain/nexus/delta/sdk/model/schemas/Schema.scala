package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

/**
  * A schema representation
  *
  * @param id        the schema identifier
  * @param project   the project where the schema belongs
  * @param tags      the schema tags
  * @param source    the representation of the schema as posted by the subject
  * @param compacted the compacted JSON-LD representation of the schema
  * @param expanded  the expanded JSON-LD representation of the schema
  * @param graph     the Graph  representation of the schema
  */
final case class Schema(
    id: Iri,
    project: ProjectRef,
    tags: Map[Label, Long],
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd,
    graph: Graph
)
