package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingCoordinator.CompositeIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.OnePage
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import monix.bio.Task

import java.time.Instant
import scala.concurrent.duration._

final class CompositeOnEventInstant(
    views: CompositeViews,
    config: CompositeViewsConfig,
    coordinator: CompositeIndexingCoordinator
) extends OnEventInstant {

  override def awakeIndexingStream(
      project: ProjectRef,
      prevEvent: Option[Instant],
      currentEvent: Instant
  ): Task[Unit] = {
    val idleTimeout = config.idleTimeout.minus(1.minute) // allow for some tolerance
    Task.when(currentEvent.diff(prevEvent.getOrElse(currentEvent)).gteq(idleTimeout)) {
      val currentProjectViews = views.list(OnePage, searchParams(project), ResourceF.defaultSort).map(_.sources)
      currentProjectViews.flatMap { viewSeq =>
        Task.parTraverse(viewSeq)(view => coordinator.run(view.id, project, view.rev)).void
      }
    }
  }

  private def searchParams(project: ProjectRef) =
    CompositeViewSearchParams(
      deprecated = Some(false),
      filter = v =>
        v.sources.value.exists {
          case _: ProjectSource      => v.project == project
          case s: CrossProjectSource => s.project == project
          case _                     => false
        }
    )

}
