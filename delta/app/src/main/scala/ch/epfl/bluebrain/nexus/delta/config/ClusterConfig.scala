package ch.epfl.bluebrain.nexus.delta.config

import akka.actor.{ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * The cluster configuration.
  * @param seeds a comma separated list of seed nodes.
  * @see [[ClusterConfig#seedList]]
  */
final case class ClusterConfig(
    seeds: Option[String]
) {

  /**
    * A well formed collection of seed nodes.
    */
  def seedList(implicit as: ActorSystem, cfg: AppConfig): List[Address] =
    seeds.toList
      .flatMap(_.split(","))
      .map(addr => AddressFromURIString(s"akka://${cfg.description.fullName}@$addr")) match {
      case Nil      => List(Cluster(as).selfAddress)
      case nonEmpty => nonEmpty
    }
}

object ClusterConfig {
  implicit final val clusterConfigReader: ConfigReader[ClusterConfig] =
    deriveReader[ClusterConfig]
}
