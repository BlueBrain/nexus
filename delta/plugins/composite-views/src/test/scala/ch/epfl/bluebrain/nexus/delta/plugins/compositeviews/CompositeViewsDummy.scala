package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{ProjectionNotFound, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.{ElasticSearchProjectionType, SparqlProjectionType}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

class CompositeViewsDummy(views: ViewResource*) {

  def fetch(id: IdSegmentRef, project: ProjectRef): IO[CompositeViewRejection, ViewResource] =
    IO.fromOption(
      views.find(v => IriSegment(v.id) == id.value && v.value.project == project),
      ViewNotFound(expandIri(id.value), project)
    )

  def fetchProjection(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewProjectionResource] =
    fetch(id, project).flatMap { v =>
      IO.fromOption(
        v.value.projections.value.collectFirst { case p if IriSegment(p.id) == projectionId => v.map(_ -> p) },
        ProjectionNotFound(v.id, expandIri(projectionId), project)
      )
    }

  def fetchElasticSearchProjection(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewElasticSearchProjectionResource] =
    fetchProjection(id, projectionId, project).flatMap { v =>
      val (view, projection) = v.value
      IO.fromOption(
        projection.asElasticSearch.map(p => v.as(view -> p)),
        ProjectionNotFound(v.id, projection.id, project, ElasticSearchProjectionType)
      )
    }

  def fetchBlazegraphProjection(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewSparqlProjectionResource] =
    fetchProjection(id, projectionId, project).flatMap { v =>
      val (view, projection) = v.value
      IO.fromOption(
        projection.asSparql.map(p => v.as(view -> p)),
        ProjectionNotFound(v.id, projection.id, project, SparqlProjectionType)
      )
    }

  private def expandIri(idSegment: IdSegment) =
    idSegment match {
      case IdSegment.StringSegment(value) => iri"http://localhost/$value"
      case IriSegment(value)              => value
    }
}
