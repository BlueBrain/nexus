package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.sourcing.PostgresSpecs
import org.scalatest.DoNotDiscover

import scala.util.Random

@DoNotDiscover
class PostgresProjectionSpec extends ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  override lazy val projections: Projection[SomeEvent] =
    Projection.postgres(PostgresSpecs.postgresConfig, SomeEvent.empty, throwableToString).runSyncUnsafe()

  override def generateOffset: Offset = Sequence(Random.nextLong())
}
