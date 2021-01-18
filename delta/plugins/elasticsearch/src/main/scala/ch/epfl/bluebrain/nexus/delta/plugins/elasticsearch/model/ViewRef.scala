package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * A view reference.
  *
  * @param project the view parent project
  * @param viewId  the view id
  */
final case class ViewRef(project: ProjectRef, viewId: Iri)

object ViewRef {

  implicit final val viewRefOrder: Order[ViewRef] =
    Order.fromOrdering(Ordering.by[ViewRef, ProjectRef](_.project).orElseBy(_.viewId))
}
