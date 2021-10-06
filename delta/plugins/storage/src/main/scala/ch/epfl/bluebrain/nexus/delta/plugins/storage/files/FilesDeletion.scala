package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.FilesAggregate
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class FilesDeletion(
    stopActor: StopActor,
    currentEvents: CurrentEvents[FileEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion[FileEvent](stopActor, currentEvents, dbCleanup, Files.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    Task.pure(CachesDeleted)

}

object FilesDeletion {

  final def apply(
      agg: FilesAggregate,
      files: Files,
      dbCleanup: DatabaseCleanup
  ): FilesDeletion =
    new FilesDeletion(
      agg.stop,
      (project, offset) =>
        files.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup
    )
}
