package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamBehaviour._
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

import scala.collection.concurrent

/**
  * Relies on cluster sharding to distribute the stream-related work between nodes and relies on remember entities for
  * restarts after a rebalance or a crash.
  *
  * @see
  *   https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html
  * @see
  *   https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#remembering-entities
  */
object DaemonStreamCoordinator {

  private val tasks: concurrent.Map[String, DaemonStream] = new concurrent.TrieMap

  /**
    * Run the provided stream under the given name
    *
    * @param name
    *   the name of the stream/entity
    * @param stream
    *   the stream to run
    * @param retryStrategy
    *   the retry strategy to apply
    */
  def run(name: String, stream: Stream[Task, Unit], retryStrategy: RetryStrategy[Throwable])(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[Unit] = {
    val disableStreams = sys.env.get("DISABLE_STREAMS").flatMap(_.toBooleanOption).getOrElse(false)
    Task.unless(disableStreams) {
      Task.delay(tasks.put(name, DaemonStream(name, stream, retryStrategy))) >>
        Task.delay {
          val settings    = ClusterShardingSettings(as).withRememberEntities(true)
          val shardingRef = ClusterSharding(as).init(
            Entity(EntityTypeKey[SupervisorCommand]("daemonStream")) { entityContext =>
              val daemon = tasks(entityContext.entityId)
              DaemonStreamBehaviour(entityContext.entityId, daemon.stream, daemon.retryStrategy)
            }.withStopMessage(Stop()).withSettings(settings)
          )

          shardingRef ! StartEntity(name)
        }
    }
  }

  final private case class DaemonStream(
      name: String,
      stream: Stream[Task, Unit],
      retryStrategy: RetryStrategy[Throwable]
  )

}
