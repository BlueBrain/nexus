package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{RevisionNotFound, SchemaNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{Schema, SchemaRejection, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

trait FetchSchema {

  /** Fetch the referenced resource in the given project */
  def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Schema]

  def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[SchemaState]

}

object FetchSchema {

  def apply(log: SchemaLog): FetchSchema = {

    def notFound(iri: Iri, ref: ProjectRef) = SchemaNotFound(iri, ref)

    new FetchSchema {
      override def fetch(ref: ResourceRef, project: ProjectRef): FetchF[Schema] = {
        stateOrNotFound(IdSegmentRef(ref), ref.iri, project)
          .attemptNarrow[SchemaRejection]
          .map(_.toOption)
          .map(_.map(_.toResource))
      }

      override def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[SchemaState] =
        id match {
          case Latest(_)        => log.stateOr(ref, iri, notFound(iri, ref))
          case Revision(_, rev) => log.stateOr(ref, iri, rev, notFound(iri, ref), RevisionNotFound)
          case Tag(_, tag)      => log.stateOr(ref, iri, tag, notFound(iri, ref), TagNotFound(tag))
        }
    }

  }

}
