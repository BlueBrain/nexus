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
    * @param strategy the strategy to build a stream
    */
  def apply(view: ViewIndex[V], strategy: IndexingStream.Strategy[V]): Stream[Task, Unit]

}

object IndexingStream {

  /**
    * The strategy to build a stream
    *
    * @param progress the progress strategy
    * @param cleanup  the cleanup strategy
    */
  final case class Strategy[+A](
      progress: ProgressStrategy = ProgressStrategy.Continue,
      cleanup: CleanupStrategy[A] = CleanupStrategy.NoCleanup
  )

  object Strategy {

    /**
      * A default indexing stream strategy
      */
    val default: Strategy[Nothing] = Strategy()
  }

  /**
    * The cleanup strategy used to remove namespaces/indices and cache references to old streams
    */
  sealed trait CleanupStrategy[+A] extends Product with Serializable

  object CleanupStrategy {

    /**
      * No cleanup needed
      */
    final case object NoCleanup extends CleanupStrategy[Nothing]

    /**
      * Cleanup required for the passed ''view''
      */
    final case class Cleanup[A](view: ViewIndex[A]) extends CleanupStrategy[A]
  }

  /**
    * Possible restart strategies to build a stream.
    */
  trait ProgressStrategy extends Product with Serializable
  object ProgressStrategy {

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
