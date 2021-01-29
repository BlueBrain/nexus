package ch.epfl.bluebrain.nexus.sourcing.projections.stream

import cats.effect.concurrent.Ref
import monix.bio.Task

sealed private[stream] trait StreamState[A, State] {

  /**
    * @return the current state
    */
  def fetch: Task[State]

  /**
    * Updates the current state when a new entry gets consumed by the stream
    *
    * @param entry the stream entry
    */
  def update(entry: A): Task[Unit]
}

private[stream] object StreamState {

  /**
    * Do not record any state
    */
  def ignore[A]: StreamState[A, Unit] = new StreamState[A, Unit] {
    override def fetch: Task[Unit]            = Task.unit
    override def update(entry: A): Task[Unit] = Task.unit
  }

  /**
    * Record the state using an underlying concurrent [[Ref]].
    *
    * @param initial the initial state
    * @param next    the function that generates a next state given a stream entry and the current state
    */
  def record[A, State](initial: State, next: (State, A) => State): Task[StreamState[A, State]] =
    Ref.of[Task, State](initial).map { ref =>
      new StreamState[A, State] {
        override def fetch: Task[State]           = ref.get
        override def update(entry: A): Task[Unit] = ref.update(next(_, entry))
      }
    }
}
