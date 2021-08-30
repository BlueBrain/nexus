package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{ElasticSearchViewAggregate, ElasticSearchViewCache}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewIsDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class ElasticSearchViewsDeletion(
    views: ElasticSearchViews,
    cache: ElasticSearchViewCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[ElasticSearchViewEvent],
    dbCleanup: DatabaseCleanup,
    serviceAccount: ServiceAccount
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, ElasticSearchViews.moduleType)(_.id) {

  implicit private val subject: Subject = serviceAccount.subject

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
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

object ElasticSearchViewsDeletion {
  final def apply(
      cache: ElasticSearchViewCache,
      agg: ElasticSearchViewAggregate,
      views: ElasticSearchViews,
      dbCleanup: DatabaseCleanup,
      serviceAccount: ServiceAccount
  ): ElasticSearchViewsDeletion =
    new ElasticSearchViewsDeletion(
      views,
      cache,
      agg.stop,
      (project, offset) =>
        views.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup,
      serviceAccount
    )
}
