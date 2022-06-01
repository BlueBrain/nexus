package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

final class DataDeletion private (
    stopActor: StopActor,
    currentEvents: CurrentEvents[ResourceEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, Resources.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    Task.pure(CachesDeleted)

}

object DataDeletion {
  final def apply(agg: ResourcesAggregate, resources: Resources, dbCleanup: DatabaseCleanup): DataDeletion =
    new DataDeletion(
      agg.stop,
      (project, offset) =>
        resources.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup
    )
}
