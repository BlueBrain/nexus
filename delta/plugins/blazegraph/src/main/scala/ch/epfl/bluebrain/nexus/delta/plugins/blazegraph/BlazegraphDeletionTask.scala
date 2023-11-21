package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDeletionTask.{init, logger}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream

/**
  * Creates a project deletion step that deprecates all views within a project when a project is deleted so that the
  * coordinator stops the running Blazegraph projections on the different Delta nodes
  */
final class BlazegraphDeletionTask(
    currentViews: ProjectRef => Stream[IO, IndexingViewDef],
    deprecate: (ActiveViewDef, Subject) => IO[Unit]
) extends ProjectDeletionTask {

  override def apply(project: ProjectRef)(implicit subject: Subject): IO[ProjectDeletionReport.Stage] =
    logger.info(s"Starting deprecation of Blazegraph views for '$project'") >>
      run(project)

  private def run(project: ProjectRef)(implicit subject: Subject) =
    currentViews(project)
      .evalScan(init) {
        case (acc, _: DeprecatedViewDef) => IO.pure(acc)
        case (acc, view: ActiveViewDef)  =>
          deprecate(view, subject).as(acc ++ s"Blazegraph view '${view.ref}' has been deprecated.")
      }
      .compile
      .lastOrError

}

object BlazegraphDeletionTask {
  private val logger = Logger[BlazegraphDeletionTask]

  private val init = ProjectDeletionReport.Stage.empty("blazegraph")

  def apply(views: BlazegraphViews) =
    new BlazegraphDeletionTask(
      project => views.currentIndexingViews(project).evalMapFilter(_.toIO),
      (v: ActiveViewDef, subject: Subject) =>
        views.internalDeprecate(v.ref.viewId, v.ref.project, v.rev)(subject).handleErrorWith { r =>
          logger.error(s"Deprecating '$v' resulted in error: '$r'.")
        }
    )

}
