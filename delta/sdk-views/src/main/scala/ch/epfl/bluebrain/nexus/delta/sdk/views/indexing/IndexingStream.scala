package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import fs2.Stream
import monix.bio.Task

/**
  * Defines how to build a stream for a view of type ''V''
  */
trait IndexingStream[V] {

  /**
    * Builds a stream from the passed parameters.
    *
    * @param view     the [[ViewIndex]]
    * @param strategy the progress strategy used to build a stream
    */
  def apply(view: ViewIndex[V], strategy: IndexingStream.ProgressStrategy): Stream[Task, Unit]

}

object IndexingStream {

  /**
    * Possible restart strategies to build a stream.
    */
  trait ProgressStrategy extends Product with Serializable
  object ProgressStrategy {

    val default: ProgressStrategy = ProgressStrategy.Continue

    /**
      * Continues from the offset it left off
      */
    final case object Continue extends ProgressStrategy

    /**
      * Restarts from the offset [[akka.persistence.query.NoOffset]]
      */
    final case object FullRestart extends ProgressStrategy

  }
}
