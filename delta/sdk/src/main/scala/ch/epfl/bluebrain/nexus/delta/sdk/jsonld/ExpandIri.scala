package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, ResourceRef, TagLabel}
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

  /**
    * Expand the given segment to a ResourceRef using the provided project if necessary and applying the revision
    * to get a resource reference
    * @param segment the to translate to an Iri
    * @param project the project
    * @param the version of the resource ref
    */
  def apply(segment: IdSegment, project: Project, version: Option[Either[Long, TagLabel]]): IO[R, ResourceRef] =
    IO.fromOption(
      segment.toIri(project.apiMappings, project.base).map { iri =>
        version match {
          case None             => ResourceRef.Latest(iri)
          case Some(Left(rev))  => ResourceRef.Revision(iri, rev)
          case Some(Right(tag)) => ResourceRef.Tag(iri, tag)
        }
      },
      onError(segment.asString)
    )
}
