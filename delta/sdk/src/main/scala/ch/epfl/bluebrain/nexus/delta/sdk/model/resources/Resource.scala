package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

/**
  * A resource representation
  *
  * @param id        the resource identifier
  * @param project   the project where the resource belongs
  * @param tags      the resource tags
  * @param schema    the schema used to constrain the resource
  * @param source    the representation of the resource as posted by the subject
  * @param compacted the compacted JSON-LD representation of the resource
  * @param expanded  the expanded JSON-LD representation of the resource
  */
final case class Resource(
    id: Iri,
    project: ProjectRef,
    tags: Map[Label, Long],
    schema: ResourceRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd
)
