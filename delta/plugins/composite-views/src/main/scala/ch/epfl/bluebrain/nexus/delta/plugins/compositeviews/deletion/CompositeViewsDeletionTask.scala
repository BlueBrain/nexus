package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion.CompositeViewsDeletionTask.{init, logger}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream

/**
  * Creates a project deletion step that deprecates all views within a project when a project is deleted so that the
  * coordinator stops the running composite view projections on the different Delta nodes
  */
final class CompositeViewsDeletionTask(
    currentViews: ProjectRef => Stream[IO, CompositeViewDef],
    deprecate: (ActiveViewDef, Subject) => IO[Unit]
) extends ProjectDeletionTask {
  override def apply(project: ProjectRef)(implicit subject: Subject): IO[ProjectDeletionReport.Stage] =
    logger.info(s"Starting deprecation of composite views for '$project'") >>
      run(project)

  private def run(project: ProjectRef)(implicit subject: Subject) =
    currentViews(project)
      .evalScan(init) {
        case (acc, _: DeprecatedViewDef) => IO.pure(acc)
        case (acc, view: ActiveViewDef)  =>
          deprecate(view, subject).as(acc ++ s"Composite view '${view.ref}' has been deprecated.")
      }
      .compile
      .lastOrError
}

object CompositeViewsDeletionTask {

  private val logger = Logger.cats[CompositeViewsDeletionTask]

  private val init = ProjectDeletionReport.Stage.empty("compositeviews")

  def apply(views: CompositeViews) =
    new CompositeViewsDeletionTask(
      project => views.currentViews(project).evalMapFilter(_.toIO),
      (v: ActiveViewDef, subject: Subject) =>
        views
          .internalDeprecate(v.ref.viewId, v.ref.project, v.rev)(subject)
          .onError { r =>
            logger.error(s"Deprecating '$v' resulted in error: '$r'.")
          }
    )

}
