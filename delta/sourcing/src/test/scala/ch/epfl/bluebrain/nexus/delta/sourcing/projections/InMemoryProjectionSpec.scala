package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{Offset, Sequence}

import scala.util.Random

class InMemoryProjectionSpec extends ProjectionSpec {

  override val projections: Projection[SomeEvent] =
    Projection.inMemory(SomeEvent.empty, throwableToString).accepted

  override def generateOffset: Offset = Sequence(Random.nextLong())
}
