package ch.epfl.bluebrain.nexus.delta.service.acls

import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.StopActor
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted, ResourcesDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, ResourcesDeletion}
import ch.epfl.bluebrain.nexus.delta.service.acls.AclsImpl.{AclsAggregate, AclsCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class AclsDeletion private (
    cache: AclsCache,
    stopActor: StopActor,
    dbCleanup: DatabaseCleanup
) extends ResourcesDeletion {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

  override def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeleted] =
    stopActor(s"/$projectRef") >>
      dbCleanup.deleteAll(Acls.moduleType, s"/$projectRef").as(ResourcesDeleted)
}

object AclsDeletion {

  final def apply(
      cache: AclsCache,
      agg: AclsAggregate,
      dbCleanup: DatabaseCleanup
  ): AclsDeletion =
    new AclsDeletion(cache, agg.stop, dbCleanup)
}
