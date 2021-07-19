package ch.epfl.bluebrain.nexus.delta.sdk

sealed trait Indexing extends Product with Serializable

object Indexing {

  final case object Async extends Indexing
  final case object Sync  extends Indexing
}
