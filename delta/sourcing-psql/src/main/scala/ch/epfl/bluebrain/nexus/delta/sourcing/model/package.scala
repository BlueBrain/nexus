package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import fs2.Stream
import monix.bio.Task

package object model {

  type EnvelopeStream[Value] = Stream[Task, Envelope[Value]]

  type ElemStream[Value] = Stream[Task, Elem[Value]]

}
