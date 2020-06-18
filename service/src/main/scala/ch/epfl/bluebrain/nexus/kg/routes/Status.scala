package ch.epfl.bluebrain.nexus.kg.routes

import akka.cluster.{Cluster, MemberStatus}
import monix.eval.Task

sealed trait Status {

  /**
    * Checks the connectivity.
    *
    * @return Future(true) when there is connectivity with the service from within the app
    *         Future(false) otherwise
    */
  def check: Task[Boolean]
}

object Status {

  class ClusterStatus(cluster: Cluster) extends Status {
    override def check: Task[Boolean] =
      Task.pure(
        !cluster.isTerminated &&
          cluster.state.leader.isDefined && cluster.state.members.nonEmpty &&
          !cluster.state.members.exists(_.status != MemberStatus.Up) && cluster.state.unreachable.isEmpty
      )
  }
}
