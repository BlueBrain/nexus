package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisor

sealed private[indexing] trait IndexingState extends Product with Serializable

private[indexing] object IndexingState {
  final case object Initial                                                        extends IndexingState
  final case class Current(index: String, rev: Long, supervisor: StreamSupervisor) extends IndexingState
}
