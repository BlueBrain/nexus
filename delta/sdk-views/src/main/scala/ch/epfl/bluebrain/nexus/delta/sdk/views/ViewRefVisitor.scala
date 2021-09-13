package ch.epfl.bluebrain.nexus.delta.sdk.views

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import monix.bio.{IO, UIO}

/**
  * Provides a way to visit recursively a collection of [[ViewRef]] while preventing circular dependencies.
  * @tparam E
  *   the error type
  */
class ViewRefVisitor[E](fetchView: (Iri, ProjectRef) => IO[E, VisitedView]) {

  private def visitOne(toVisit: ViewRef, visited: Set[VisitedView]): IO[E, Set[VisitedView]] =
    fetchView(toVisit.viewId, toVisit.project).flatMap {
      case view: IndexedVisitedView    => IO.pure(Set(view))
      case view: AggregatedVisitedView => visitAll(view.viewRefs, visited + view)
    }

  private def visitAll(toVisit: NonEmptySet[ViewRef], visited: Set[VisitedView]): IO[E, Set[VisitedView]] =
    toVisit.value.toList.foldM(visited) {
      case (visited, viewToVisit) if visited.exists(_.ref == viewToVisit) => UIO.pure(visited)
      case (visited, viewToVisit)                                         => visitOne(viewToVisit, visited).map(visited ++ _)
    }

  def visitAll(toVisit: NonEmptySet[ViewRef]): IO[E, Set[VisitedView]] = visitAll(toVisit, Set.empty)
}

object ViewRefVisitor {

  /**
    * Enumeration of possible visited views
    */
  sealed trait VisitedView extends Product with Serializable {

    /**
      * @return
      *   the reference to the current visited view
      */
    def ref: ViewRef

    def isIndexed: Boolean
  }

  object VisitedView {

    /**
      * A visited indexed view
      */
    final case class IndexedVisitedView(ref: ViewRef, permission: Permission, index: String) extends VisitedView {
      override val isIndexed: Boolean = true
    }

    /**
      * A visited aggregated view
      */
    final case class AggregatedVisitedView(ref: ViewRef, viewRefs: NonEmptySet[ViewRef]) extends VisitedView {
      override val isIndexed: Boolean = false
    }
  }
}
