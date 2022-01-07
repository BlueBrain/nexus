package ch.epfl.bluebrain.nexus.delta.sourcing2

import io.circe.Encoder
import monix.bio.UIO

trait WriteJournal {

  def write[Id, Event, State](event: Event, state: State)(implicit
      eventEncoder: Encoder[Event],
      stateEncoder: Encoder[State]
  ): UIO[Unit]

}

object WriteJournal {}
