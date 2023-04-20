package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDeletionTask.{init, logger}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}

/**
  * Creates a project deletion step that deprecates all views within a project when a project is deleted so that the
  * coordinator stops the running Elasticsearch projections on the different Delta nodes
  */
final class ElasticSearchDeletionTask(
    currentViews: ProjectRef => Stream[Task, IndexingViewDef],
    deprecate: (ActiveViewDef, Subject) => UIO[Unit]
) extends ProjectDeletionTask {

  override def apply(project: ProjectRef)(implicit subject: Subject): Task[ProjectDeletionReport.Stage] =
    UIO.delay(logger.info(s"Starting deprecation of Elasticsearch views for $project")) >>
      run(project)

  private def run(project: ProjectRef)(implicit subject: Subject) =
    currentViews(project)
      .evalScan(init) {
        case (acc, view: DeprecatedViewDef) =>
          UIO.delay(logger.info(s"Elasticsearch view '${view.ref}' is already deprecated.")).as(acc)
        case (acc, view: ActiveViewDef)     =>
          deprecate(view, subject).as(acc ++ s"Elasticsearch view '${view.ref}' has been deprecated.")
      }
      .compile
      .lastOrError
}

object ElasticSearchDeletionTask {

  private val logger: Logger = Logger[ElasticSearchDeletionTask]

  private val init = ProjectDeletionReport.Stage.empty("elasticsearch")

  def apply(views: ElasticSearchViews) =
    new ElasticSearchDeletionTask(
      project => views.currentIndexingViews(project).evalMapFilter(_.toTask),
      (v: ActiveViewDef, subject: Subject) =>
        views.internalDeprecate(v.ref.viewId, v.ref.project, v.rev)(subject).onErrorHandleWith { r =>
          UIO.delay(logger.error(s"Deprecating '$v' resulted in error: '$r'."))
        }
    )

}
