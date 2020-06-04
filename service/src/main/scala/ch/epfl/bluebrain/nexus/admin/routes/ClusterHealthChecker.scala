package ch.epfl.bluebrain.nexus.admin.routes

import akka.cluster.{Cluster, MemberStatus}
import ch.epfl.bluebrain.nexus.admin.routes.HealthChecker._

import scala.concurrent.Future

/**
  *  The Akka cluster health checker
  */
class ClusterHealthChecker(cluster: Cluster) extends HealthChecker {
  override def check: Future[Status] =
    if (!cluster.isTerminated &&
        cluster.state.leader.isDefined &&
        cluster.state.members.nonEmpty &&
        !cluster.state.members.exists(_.status != MemberStatus.Up) &&
        cluster.state.unreachable.isEmpty) Future.successful(Up)
    else Future.successful(Inaccessible)
}

object ClusterHealthChecker {
  def apply(cluster: Cluster): ClusterHealthChecker = new ClusterHealthChecker(cluster)
}
