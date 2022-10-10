package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.{EveryNode, SingleNode}

/**
  * Determines how projections should be executed, namely if the current node must run this projection and if offsets
  * should be persisted which is available only if a projection is run on a single node.
  */
sealed trait ExecutionStrategy extends Product with Serializable {

  /**
    * True if the projection must run on all the nodes or if the hash of the projection name modulo number of nodes
    * matches the current node.
    *
    * @param name
    *   the name of the projection
    *
    * @param cluster
    *   the cluster configuration
    */
  def shouldRun(name: String, cluster: ClusterConfig): Boolean = this match {
    case _: SingleNode => name.hashCode % cluster.size == cluster.nodeIndex
    case EveryNode     => true
  }
}

object ExecutionStrategy {

  /**
    * Strategy for projections that must run on a single node with optional offset persistence.
    */
  sealed trait SingleNode extends ExecutionStrategy

  /**
    * Strategy for projections that must run on a single node without persisting the offset.
    */
  final case object TransientSingleNode extends SingleNode
  type TransientSingleNode = TransientSingleNode.type

  /**
    * Strategy for projections that must run on a single node persisting the offset.
    */
  final case object PersistentSingleNode extends SingleNode
  type PersistentSingleNode = PersistentSingleNode.type

  /**
    * Strategy for projections that must run on all the nodes, useful for populating caches.
    */
  final case object EveryNode extends ExecutionStrategy
  type EveryNode = EveryNode.type
}
