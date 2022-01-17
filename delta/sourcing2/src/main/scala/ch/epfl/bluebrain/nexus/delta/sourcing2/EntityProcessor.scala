package ch.epfl.bluebrain.nexus.delta.sourcing2

import monix.bio.IO

sealed trait StateReplay[Event, State] {

  def initialState: Option[State]

  def next(s: Option[State], e: Event): Option[State]
}

sealed trait EntityProcessor[Command, Event, State, Rejection] extends StateReplay[Event, State] {

  def evaluate(s: Option[State], c: Command): IO[Rejection, Event]

}

object EntityProcessor {

  def apply[Command, Event, State, Rejection](
      i: Option[State],
      e: (Option[State], Command) => IO[Rejection, Event],
      n: (Option[State], Event) => Option[State]
  ): EntityProcessor[Command, Event, State, Rejection] = new EntityProcessor[Command, Event, State, Rejection] {

    override def initialState: Option[State] = i

    override def evaluate(s: Option[State], c: Command): IO[Rejection, Event] = e(s, c)

    override def next(s: Option[State], e: Event): Option[State] = n(s, e)
  }

}
