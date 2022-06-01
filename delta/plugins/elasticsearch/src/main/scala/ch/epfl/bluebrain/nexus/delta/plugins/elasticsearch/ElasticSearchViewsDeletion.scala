package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{ElasticSearchViewAggregate, ElasticSearchViewCache}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.ElasticSearchIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ElasticSearchViewType}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamAwake
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

final class ElasticSearchViewsDeletion(
    cache: ElasticSearchViewCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[ElasticSearchViewEvent],
    dbCleanup: DatabaseCleanup,
    coordinator: ElasticSearchIndexingCoordinator,
    indexingStreamAwake: IndexingStreamAwake
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, ElasticSearchViews.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    cache
      .values(projectRef)
      .flatMap { viewsList =>
        Task.traverse(viewsList) { view =>
          view.value.tpe match {
            case ElasticSearchViewType.ElasticSearch          =>
              coordinator.cleanUpAndStop(view.id, projectRef)
            case ElasticSearchViewType.AggregateElasticSearch =>
              Task.unit
          }
        }
      } >> indexingStreamAwake
      .remove(projectRef)
      .as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

}

object ElasticSearchViewsDeletion {
  final def apply(
      cache: ElasticSearchViewCache,
      agg: ElasticSearchViewAggregate,
      views: ElasticSearchViews,
      dbCleanup: DatabaseCleanup,
      coordinator: ElasticSearchIndexingCoordinator,
      indexingStreamAwake: IndexingStreamAwake
  ): ElasticSearchViewsDeletion =
    new ElasticSearchViewsDeletion(
      cache,
      agg.stop,
      (project, offset) =>
        views.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup,
      coordinator,
      indexingStreamAwake
    )
}
