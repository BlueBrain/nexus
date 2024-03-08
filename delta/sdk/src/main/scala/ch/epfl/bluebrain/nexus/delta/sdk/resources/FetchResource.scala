package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ScopedResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{ResourceNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

trait FetchResource {

  /** Fetch the referenced resource in the given project */
  def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Resource]

  def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[ResourceState]

}

object FetchResource {

  def apply(log: ScopedResourceLog): FetchResource = {

    def notFound(iri: Iri, ref: ProjectRef) = ResourceNotFound(iri, ref)

    new FetchResource {
      override def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Resource] = {
        stateOrNotFound(IdSegmentRef(ref), ref.iri, project)
          .attemptNarrow[ResourceRejection]
          .map(_.toOption)
          .map(_.map(_.toResource))
      }

      override def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[ResourceState] =
        id match {
          case Latest(_)        => log.stateOr(ref, iri, notFound(iri, ref))
          case Revision(_, rev) => log.stateOr(ref, iri, rev, notFound(iri, ref), RevisionNotFound)
          case Tag(_, tag)      => log.stateOr(ref, iri, tag, notFound(iri, ref), TagNotFound(tag))
        }
    }

  }

}
