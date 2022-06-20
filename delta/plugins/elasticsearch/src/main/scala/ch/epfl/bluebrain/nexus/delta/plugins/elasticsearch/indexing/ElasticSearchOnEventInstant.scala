package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.ElasticSearchIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewSearchParams, ElasticSearchViewType}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.OnePage
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{Task, UIO}

import java.time.Instant
import scala.concurrent.duration._

final class ElasticSearchOnEventInstant(
    views: ElasticSearchViews,
    config: ElasticSearchViewsConfig,
    coordinator: ElasticSearchIndexingCoordinator
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
    ElasticSearchViewSearchParams(
      project = Some(project),
      deprecated = Some(false),
      filter = v => UIO.pure(v.tpe == ElasticSearchViewType.ElasticSearch)
    )

}
