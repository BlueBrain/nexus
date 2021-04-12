package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{ProjectionNotFound, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewRejection, ViewProjectionResource, ViewResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.IO

class CompositeViewsDummy(views: ViewResource*) {

  def fetch(id: IdSegment, project: ProjectRef): IO[CompositeViewRejection, ViewResource] =
    IO.fromOption(
      views.find(v => IriSegment(v.id) == id && v.value.project == project),
      ViewNotFound(expandIri(id), project)
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

  private def expandIri(idSegment: IdSegment) =
    idSegment match {
      case IdSegment.StringSegment(value) => iri"http://localhost/$value"
      case IriSegment(value)              => value
    }
}
