package ch.epfl.bluebrain.nexus.delta.sourcing

import fs2.Stream
import monix.bio.Task

package object model {

  type EnvelopeStream[Id, Value] = Stream[Task, Envelope[Id, Value]]

}
