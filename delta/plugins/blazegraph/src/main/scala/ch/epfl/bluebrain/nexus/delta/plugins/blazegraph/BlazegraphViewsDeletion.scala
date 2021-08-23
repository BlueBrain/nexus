package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{BlazegraphViewsAggregate, BlazegraphViewsCache}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ViewIsDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class BlazegraphViewsDeletion(
    views: BlazegraphViews,
    cache: BlazegraphViewsCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[BlazegraphViewEvent],
    dbCleanup: DatabaseCleanup,
    serviceAccount: ServiceAccount
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, BlazegraphViews.moduleType)(_.id) {

  implicit private val subject: Subject = serviceAccount.subject

  override def deleteData(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    cache
      .values(projectRef)
      .flatMap { viewsList =>
        Task.traverse(viewsList) { view =>
          views
            .deprecateWithoutProjectChecks(view.id, projectRef, view.rev)
            .void
            .onErrorHandleWith {
              case _: ViewIsDeprecated => Task.unit
              case err                 => Task.raiseError(new IllegalArgumentException(err.reason))
            }
        }
      }
      .as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

}

object BlazegraphViewsDeletion {
  final def apply(
      cache: BlazegraphViewsCache,
      agg: BlazegraphViewsAggregate,
      views: BlazegraphViews,
      dbCleanup: DatabaseCleanup,
      serviceAccount: ServiceAccount
  ): BlazegraphViewsDeletion =
    new BlazegraphViewsDeletion(
      views,
      cache,
      agg.stop,
      (project, offset) =>
        views.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup,
      serviceAccount
    )
}
