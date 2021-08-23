package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.ActorSystem
import akka.persistence.cassandra.cleanup.Cleanup
import akka.persistence.query.NoOffset
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF.defaultSort
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.OnePage
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.{Aggregate, EventLog}
import monix.bio.{IO, Task}

import scala.concurrent.duration._

class DeleteProject(
    elasticSearchViews: ElasticSearchViews,
    blazegraphViews: BlazegraphViews,
    compositeViews: CompositeViews,
    projects: Projects,
    eventLog: EventLog[Envelope[ProjectScopedEvent]],
    aggregate: Aggregate[String, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]
)(implicit system: ActorSystem) {
  private def elasticSearchFilter(projectRef: ProjectRef) =
    ElasticSearchViewSearchParams(Some(projectRef), deprecated = Some(false), filter = _ => true)

  private def blazegraphFilter(projectRef: ProjectRef) =
    BlazegraphViewSearchParams(Some(projectRef), deprecated = Some(false), filter = _ => true)

  private def compositeFilter(projectRef: ProjectRef) =
    CompositeViewSearchParams(Some(projectRef), deprecated = Some(false), filter = _ => true)

  val cleanup = new Cleanup(system)

  // Deprecate all views (deletes indices & namespaces)
  // Delete all cache references
  // Update the project counts accordingly (remove its cache and restart the stream, wiping its offset)
  // Take care of aggregated views which have a reference to deleted projects

  def toThrowable[A](value: A): Throwable = new IllegalArgumentException(value.toString)

  def delete(p: ProjectRef, rev: Long)(implicit subject: Subject): Task[Unit] = {
    for {
      list   <- elasticSearchViews.list(OnePage, elasticSearchFilter(p), defaultSort)
      _      <- IO.traverse(list.sources)(v => elasticSearchViews.deprecate(v.id, p, v.rev)).mapError(toThrowable)
      list   <- blazegraphViews.list(OnePage, blazegraphFilter(p), defaultSort)
      _      <- IO.traverse(list.sources)(v => blazegraphViews.deprecate(v.id, p, v.rev)).mapError(toThrowable)
      list   <- compositeViews.list(OnePage, compositeFilter(p), defaultSort)
      _      <- IO.traverse(list.sources)(v => compositeViews.deprecate(v.id, p, v.rev)).mapError(toThrowable)
      _      <- projects.deprecate(p, rev).mapError(toThrowable)
      stream <- eventLog.currentProjectEvents(projects, p, NoOffset).mapError(toThrowable)
      _      <-
        stream
          .groupWithin(10, 5.seconds)
          .mapAsync(10)(chunks =>
            Task.traverse(chunks.toList.filter(_.persistenceId.startsWith("resource-")))(env =>
              aggregate.stop(env.persistenceId)
            ) >>
              Task.deferFuture(
                cleanup.deleteAll(chunks.map(_.persistenceId).toList, neverUsePersistenceIdAgain = false)
              )
          )
          .compile
          .drain
    } yield ()

  }

}
