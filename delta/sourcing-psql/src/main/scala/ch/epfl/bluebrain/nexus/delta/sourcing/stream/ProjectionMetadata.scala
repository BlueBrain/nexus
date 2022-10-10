package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

final case class ProjectionMetadata(module: String,
                                    name: String,
                                    project: Option[ProjectRef],
                                    resourceId: Option[Iri]) {

}
