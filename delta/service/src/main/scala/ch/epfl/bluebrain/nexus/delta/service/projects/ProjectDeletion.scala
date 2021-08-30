package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.StopActor
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted, ResourcesDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, ProjectsCounts, ResourcesDeletion}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class ProjectDeletion private (
    cache: ProjectsCache,
    stopActor: StopActor,
    projectCounts: ProjectsCounts,
    dbCleanup: DatabaseCleanup
) extends ResourcesDeletion {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef) >> projectCounts.remove(projectRef) >> Task.pure(CachesDeleted)

  override def deleteRegistry(projectRef: ProjectRef): Task[ResourcesDeleted] =
    stopActor(projectRef.toString) >>
      dbCleanup.deleteAll(Projects.moduleType, UrlUtils.encode(projectRef.toString)).as(ResourcesDeleted)
}

object ProjectDeletion {
  final def apply(
      cache: ProjectsCache,
      agg: ProjectsAggregate,
      projectCounts: ProjectsCounts,
      dbCleanup: DatabaseCleanup
  ): ProjectDeletion =
    new ProjectDeletion(cache, agg.stop, projectCounts, dbCleanup)
}
