package ch.epfl.bluebrain.nexus.delta.sdk.indexing

sealed private[indexing] trait IndexingCommand extends Product with Serializable

object IndexingCommand {
  final private[indexing] case class StartIndexing[V](view: V) extends IndexingCommand
  final private[indexing] case object StopIndexing             extends IndexingCommand
}
