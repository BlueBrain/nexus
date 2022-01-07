package ch.epfl.bluebrain.nexus.delta.sourcing2

import monix.bio.IO

sealed trait StateReplay[Event, State] {

  def initialState: Option[State]

  def next(s: Option[State], e: Event): State
}

sealed trait EventProcessor[Command, Event, State, Rejection] extends StateReplay[Event, State] {

  def evaluate(s: Option[State], c: Command): IO[Rejection, Event]

}

object EventProcessor {

  def apply[Command, Event, State, Rejection](
      e: (Option[State], Command) => IO[Rejection, Event],
      n: (Option[State], Event) => State
  ): EventProcessor[Command, Event, State, Rejection] = apply(None, e, n)

  def apply[Command, Event, State, Rejection](
      i: Option[State],
      e: (Option[State], Command) => IO[Rejection, Event],
      n: (Option[State], Event) => State
  ): EventProcessor[Command, Event, State, Rejection] = new EventProcessor[Command, Event, State, Rejection] {

    override def initialState: Option[State] = i

    override def evaluate(s: Option[State], c: Command): IO[Rejection, Event] = e(s, c)

    override def next(s: Option[State], e: Event): State = n(s, e)
  }

}
