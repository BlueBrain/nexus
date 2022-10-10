package ch.epfl.bluebrain.nexus.delta.sourcing.implicits

import cats.Order

import java.time.Instant

object InstantInstances {

  implicit final val instantInstances: Order[Instant] =
    (x: Instant, y: Instant) => x.compareTo(y)

}
