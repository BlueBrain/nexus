package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import monix.bio.IO

final class ExpandIri[R](val onError: String => R) extends AnyVal {

  /**
    * Expand the given segment to an Iri using the provided project if necessary
    * @param segment the to translate to an Iri
    * @param project the project
    */
  def apply(segment: IdSegment, project: Project): IO[R, Iri] =
    IO.fromOption(
      segment.toIri(project.apiMappings, project.base),
      onError(segment.asString)
    )
}
