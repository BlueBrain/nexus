package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import cats.effect.concurrent.Ref
import monix.bio.Task

sealed private[stream] trait StreamState[A] {

  /**
    * @return the current state
    */
  def fetch: Task[A]

  /**
    * Updates the current state when a new entry gets consumed by the stream
    *
    * @param entry the new state entry
    */
  def set(entry: A): Task[Unit]
}

private[stream] object StreamState {

  /**
    * Do not record any state
    */
  def ignore[A]: StreamState[A] = new StreamState[A] {
    override def fetch: Task[A]            = Task.raiseError(new UnsupportedOperationException("fetch not supported"))
    override def set(entry: A): Task[Unit] = Task.unit
  }

  /**
    * Record the state using an underlying concurrent [[Ref]].
    *
    * @param initial the initial state
    */
  def record[A](initial: A): Task[StreamState[A]] =
    Ref.of[Task, A](initial).map { ref =>
      new StreamState[A] {
        override def fetch: Task[A]            = ref.get
        override def set(entry: A): Task[Unit] = ref.set(entry)
      }
    }
}
