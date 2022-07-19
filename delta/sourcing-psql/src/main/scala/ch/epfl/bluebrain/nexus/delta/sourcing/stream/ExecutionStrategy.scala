package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.{EveryNode, SingleNode}

/**
  * Determines how projections should be executed, namely if the current node must run this projection and if offsets
  * should be persisted which is available only if a projection is run on a single node.
  */
sealed trait ExecutionStrategy extends Product with Serializable {

  /**
    * True if the projection must run on all the nodes or if the hash of the projection name modulo number of nodes
    * matches the current node.
    * @param name
    *   the name of the projection
    * @param config
    *   the projection config
    */
  def shouldRun(name: String, config: ProjectionConfig): Boolean = this match {
    case SingleNode(_) => name.hashCode % config.clusterSize == config.nodeIndex
    case EveryNode     => true
  }

  /**
    * True if the projection is being run on a single node and should persist its offsets.
    */
  def shouldPersist: Boolean = this match {
    case SingleNode(persistOffsets) => persistOffsets
    case EveryNode                  => false
  }
}

object ExecutionStrategy {

  /**
    * Strategy for projections that must run on a single node with optional offset persistence.
    * @param persistOffsets
    *   whether offsets should be persisted
    */
  final case class SingleNode(persistOffsets: Boolean) extends ExecutionStrategy

  /**
    * Strategy for projections that must run on all the nodes, useful for populating caches.
    */
  final case object EveryNode extends ExecutionStrategy
  type EveryNode = EveryNode.type
}
