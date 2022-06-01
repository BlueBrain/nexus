package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent
import ch.epfl.bluebrain.nexus.delta.service.resolvers.ResolversImpl.{ResolversAggregate, ResolversCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

final class ResolversDeletion private (
    cache: ResolversCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[ResolverEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, Resolvers.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

}

object ResolversDeletion {
  final def apply(
      cache: ResolversCache,
      agg: ResolversAggregate,
      resolvers: Resolvers,
      dbCleanup: DatabaseCleanup
  ): ResolversDeletion =
    new ResolversDeletion(
      cache,
      agg.stop,
      (project, offset) =>
        resolvers.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup
    )
}
