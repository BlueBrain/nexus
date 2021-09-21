package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, ResourceRef}
import monix.bio.IO

final class ExpandIri[R](val onError: String => R) extends AnyVal {

  /**
    * Expand the given segment to an Iri using the provided project if necessary
    * @param segment
    *   the to translate to an Iri
    * @param project
    *   the project
    */
  def apply(segment: IdSegment, project: Project): IO[R, Iri] =
    apply(IdSegmentRef(segment), project).map(_.iri)

  /**
    * Expand the given segment to a ResourceRef using the provided project if necessary and applying the revision to get
    * a resource reference
    *
    * @param segment
    *   the segment to translate to an Iri with its optional rev/tag
    * @param project
    *   the project
    */
  def apply(segment: IdSegmentRef, project: Project): IO[R, ResourceRef] =
    IO.fromOption(
      segment.value.toIri(project.apiMappings, project.base).map { iri =>
        segment match {
          case IdSegmentRef.Latest(_)        => ResourceRef.Latest(iri)
          case IdSegmentRef.Revision(_, rev) => ResourceRef.Revision(iri, rev)
          case IdSegmentRef.Tag(_, tag)      => ResourceRef.Tag(iri, tag)
        }
      },
      onError(segment.value.asString)
    )
}
