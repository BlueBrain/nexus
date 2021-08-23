package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews.{CompositeViewsAggregate, CompositeViewsCache}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewEvent, ViewResource}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.{Task, UIO}

final class CompositeViewsDeletion(
    views: CompositeViews,
    cache: CompositeViewsCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[CompositeViewEvent],
    dbCleanup: DatabaseCleanup,
    serviceAccount: ServiceAccount
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, CompositeViews.moduleType)(_.id) {

  implicit private val subject: Subject = serviceAccount.subject

  override def deleteData(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    getViews(projectRef)
      .flatMap { viewsList =>
        Task.traverse(viewsList) { view =>
          views
            .deprecateWithoutProjectChecks(view.id, projectRef, view.rev)
            .mapError(rej => new IllegalArgumentException(rej.reason))
        }
      }
      .as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    getViews(projectRef)
      .flatMap(views => Task.traverse(views)(view => cache.remove(ViewRef(projectRef, view.id))))
      .as(CachesDeleted)

  private def getViews(projectRef: ProjectRef): UIO[Vector[ViewResource]] =
    cache.values.map(_.filter(_.value.project == projectRef))

}

object CompositeViewsDeletion {
  final def apply(
      cache: CompositeViewsCache,
      agg: CompositeViewsAggregate,
      views: CompositeViews,
      dbCleanup: DatabaseCleanup,
      serviceAccount: ServiceAccount
  ): CompositeViewsDeletion =
    new CompositeViewsDeletion(
      views,
      cache,
      agg.stop,
      (project, offset) =>
        views.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup,
      serviceAccount
    )
}
