package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import monix.bio.UIO

/**
  * Cleanup of indexing information (index and projection caches) attempted when a view gets deprecated or a new view gets created and the previous one should be cleared out
  */
trait IndexingCleanup[V] {
  def apply(view: ViewIndex[V]): UIO[Unit]
}
