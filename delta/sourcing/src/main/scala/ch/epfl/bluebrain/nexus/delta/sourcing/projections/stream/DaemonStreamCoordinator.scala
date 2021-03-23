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
  * Relies on cluster sharding to distribute the stream-related work between nodes
  * and relies on remember entities for restarts after a rebalance or a crash.
  */
object DaemonStreamCoordinator {

  private val tasks: concurrent.Map[String, DaemonStreamTask] = new concurrent.TrieMap

  /**
    * Run the provided stream under the given name
    * @param name the name of the stream/entity
    * @param streamTask the stream to run
    * @param retryStrategy the retry strategy to apply
    */
  def run(name: String, streamTask: Task[Stream[Task, Unit]], retryStrategy: RetryStrategy[Throwable])(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[Unit] =
    Task.delay {
      tasks.put(name, DaemonStreamTask(name, streamTask, retryStrategy))
    } >>
      Task.delay {
        val settings    = ClusterShardingSettings(as).withRememberEntities(true)
        val shardingRef = ClusterSharding(as).init(
          Entity(EntityTypeKey[SupervisorCommand]("daemonStream")) { entityContext =>
            val task = tasks(entityContext.entityId)
            DaemonStreamBehaviour(
              entityContext.entityId,
              task.streamTask,
              task.retryStrategy
            )
          }.withStopMessage(Stop()).withSettings(settings)
        )

        shardingRef ! StartEntity(name)
      }

  final private case class DaemonStreamTask(
      name: String,
      streamTask: Task[Stream[Task, Unit]],
      retryStrategy: RetryStrategy[Throwable]
  )

}
