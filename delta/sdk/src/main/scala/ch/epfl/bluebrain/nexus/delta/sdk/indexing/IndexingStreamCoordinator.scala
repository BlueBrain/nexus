package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamBehaviour._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.views.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

/**
  * Relies on cluster sharding to distribute the indexing work between nodes
  * and relies on remember entities for restarts after a rebalance or a crash.
  * @param viewType  type of view
  * @param fetchView  how to fetch view metadata for indexing
  * @param buildStream  how to build the indexing stream
  * @param clearIndex  how to clear the index during restarts
  * @param projection  to save the progress of indexing
  * @param retryStrategy the retry strategy to apply
  */
final class IndexingStreamCoordinator[V](
    viewType: String,
    fetchView: (Iri, ProjectRef) => UIO[Option[ViewIndex[V]]],
    buildStream: IndexingStream[V],
    clearIndex: ClearIndex,
    projection: Projection[Unit],
    retryStrategy: RetryStrategy[Throwable]
)(implicit uuidF: UUIDF, as: ActorSystem[Nothing], scheduler: Scheduler) {

  private val key = EntityTypeKey[IndexingViewCommand[V]](s"${viewType}Coordinator")

  /**
    * Runs the indexing work for the given view, starting it if needed
    */
  def run(id: Iri, project: ProjectRef, rev: Long): Task[Unit] =
    Task.delay {
      val clusterSharding = ClusterSharding(as)
      val settings        = ClusterShardingSettings(as).withRememberEntities(true)
      clusterSharding.init(
        Entity(key) { entityContext =>
          val (p, i) = parseEntityId(entityContext.entityId)
          IndexingStreamBehaviour[V](
            entityContext.shard,
            p,
            i,
            fetchView,
            buildStream,
            clearIndex,
            projection,
            retryStrategy
          )
        }.withStopMessage(Stop)
          .withSettings(settings)
      )

      clusterSharding.entityRefFor(key, entityId(project, id)) ! ViewRevision(rev)
    }

  def send(id: Iri, project: ProjectRef, command: IndexingViewCommand[V]): UIO[Unit] = UIO.delay {
    val clusterSharding = ClusterSharding(as)
    clusterSharding.entityRefFor(key, entityId(project, id)) ! command
  }

  def restart(id: Iri, project: ProjectRef): UIO[Unit] = send(id, project, Restart)

  private def entityId(project: ProjectRef, iri: Iri) = s"${project}|$iri"

  private def parseEntityId(s: String) = {
    s.split("\\|", 2) match {
      case Array(p, i) =>
        for {
          projectRef <- ProjectRef.parse(p)
          id         <- Iri(i)
        } yield projectRef -> id
      case _           =>
        Left(s"The project reference and the id could not be parsed from '$s'")
    }
  }.valueOr { s => throw new IllegalArgumentException(s) }

}
