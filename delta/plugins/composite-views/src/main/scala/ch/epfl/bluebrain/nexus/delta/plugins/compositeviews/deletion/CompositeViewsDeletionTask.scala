package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion.CompositeViewsDeletionTask.{init, logger}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}

final class CompositeViewsDeletionTask(
    currentViews: ProjectRef => Stream[Task, CompositeViewDef],
    deprecate: (ActiveViewDef, Subject) => UIO[Unit]
) extends ProjectDeletionTask {
  override def apply(project: ProjectRef)(implicit subject: Subject): Task[ProjectDeletionReport.Stage] =
    UIO.delay(logger.info(s"Starting deprecation of Composite views for $project")) >>
      run(project)

  private def run(project: ProjectRef)(implicit subject: Subject) =
    currentViews(project)
      .evalScan(init) {
        case (acc, view: DeprecatedViewDef) =>
          UIO.delay(logger.info(s"Composite view '${view.ref}' is already deprecated.")).as(acc)
        case (acc, view: ActiveViewDef)     =>
          deprecate(view, subject).as(acc ++ s"Composite view '${view.ref}' has been deprecated.")
      }
      .compile
      .lastOrError
}

object CompositeViewsDeletionTask {

  private val logger: Logger = Logger[CompositeViewsDeletionTask]

  private val init = ProjectDeletionReport.Stage.empty("compositeviews")

  def apply(views: CompositeViews) =
    new CompositeViewsDeletionTask(
      project => views.currentViews(project).evalMapFilter(_.toTask),
      (v: ActiveViewDef, subject: Subject) =>
        views.internalDeprecate(v.ref.viewId, v.ref.project, v.rev)(subject).onErrorHandleWith { r =>
          UIO.delay(logger.error(s"Deprecating '$v' resulted in error: '$r'."))
        }
    )

}
