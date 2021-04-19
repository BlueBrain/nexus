package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamBehaviour._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

/**
  * Relies on cluster sharding to distribute the indexing work between nodes
  * and relies on remember entities for restarts after a rebalance or a crash.
  *
  * @see https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html
  * @see https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#remembering-entities
  * @param controller    how to send messages to the [[IndexingStreamCoordinator]] sharded actor
  * @param fetchView     how to fetch view metadata for indexing
  * @param buildStream   how to build the indexing stream
  * @param retryStrategy the retry strategy to apply
  */
final class IndexingStreamCoordinator[V](
    controller: IndexingStreamController[V],
    fetchView: (Iri, ProjectRef) => UIO[Option[ViewIndex[V]]],
    buildStream: IndexingStream[V],
    retryStrategy: RetryStrategy[Throwable]
)(implicit uuidF: UUIDF, as: ActorSystem[Nothing], scheduler: Scheduler) {

  /**
    * Runs the indexing work for the given view, starting it if needed
    */
  def run(id: Iri, project: ProjectRef, rev: Long): Task[Unit] =
    Task.delay {
      val clusterSharding = ClusterSharding(as)
      val settings        = ClusterShardingSettings(as).withRememberEntities(true)
      clusterSharding.init(
        Entity(controller.key) { entityContext =>
          val (projectRef, iri) = parseEntityId(entityContext.entityId)
          IndexingStreamBehaviour(entityContext.shard, projectRef, iri, fetchView, buildStream, retryStrategy)
        }.withStopMessage(Stop).withSettings(settings)
      )

      clusterSharding.entityRefFor(controller.key, controller.entityId(project, id)) ! ViewRevision(rev)
    }

  private def parseEntityId(s: String) = {
    s.split("\\|", 2) match {
      case Array(p, i) => (ProjectRef.parse(p), Iri(i)).mapN { _ -> _ }
      case _           => Left(s"The project reference and the id could not be parsed from '$s'")
    }
  }.valueOr { s => throw new IllegalArgumentException(s) }

}
