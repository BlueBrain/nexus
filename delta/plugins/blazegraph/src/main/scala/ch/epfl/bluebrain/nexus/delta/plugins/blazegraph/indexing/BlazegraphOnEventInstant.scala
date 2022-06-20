package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.BlazegraphIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewSearchParams, BlazegraphViewType, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.OnePage
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{Task, UIO}

import scala.concurrent.duration._
import java.time.Instant

final class BlazegraphOnEventInstant(
    views: BlazegraphViews,
    config: BlazegraphViewsConfig,
    coordinator: BlazegraphIndexingCoordinator
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
    BlazegraphViewSearchParams(
      project = Some(project),
      deprecated = Some(false),
      filter = v => UIO.pure(v.tpe == BlazegraphViewType.IndexingBlazegraphView)
    )

}
