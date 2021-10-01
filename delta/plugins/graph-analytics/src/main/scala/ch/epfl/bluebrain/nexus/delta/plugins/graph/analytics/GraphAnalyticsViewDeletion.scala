package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingCoordinator.GraphAnalyticsIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted, ResourcesDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.Task

class GraphAnalyticsViewDeletion(coordinator: GraphAnalyticsIndexingCoordinator) extends ResourcesDeletion {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    coordinator.cleanUpAndStop(GraphAnalytics.typeStats, projectRef).as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] = Task.pure(CachesDeleted)

  override def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeleted] = Task.pure(ResourcesDeleted)
}
