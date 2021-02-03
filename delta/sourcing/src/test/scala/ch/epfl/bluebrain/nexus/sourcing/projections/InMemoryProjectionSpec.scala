package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{Offset, Sequence}
import monix.bio.Task

import scala.util.Random

class InMemoryProjectionSpec extends ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  override val projections: Projection[SomeEvent] =
    Projection.inMemory(SomeEvent.empty, throwableToString).runSyncUnsafe()

  override def configureSchema: Task[Unit] = Task.unit

  override def generateOffset: Offset = Sequence(Random.nextLong())
}
