package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

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

  // required for NonEmptySet
  // sort by project first and then by view id
  implicit final val viewRefOrder: Order[ViewRef] =
    Order.by { case ViewRef(project, viewId) =>
      (project, viewId)
    }
}
