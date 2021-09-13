package ch.epfl.bluebrain.nexus.delta.sdk

/**
  * Enumeration of all possible indexing modes
  */
sealed trait IndexingMode extends Product with Serializable

object IndexingMode {

  /**
    * Asynchronously indexing resources
    */
  final case object Async extends IndexingMode

  /**
    * Synchronously indexing resources
    */
  final case object Sync extends IndexingMode
}
