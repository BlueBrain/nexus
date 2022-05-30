package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.sourcing.PostgresSpecs
import org.scalatest.DoNotDiscover

import scala.util.Random

@DoNotDiscover
class PostgresProjectionSpec(docker: PostgresSpecs) extends ProjectionSpec {

  override lazy val projections: Projection[SomeEvent] =
    Projection.postgres(docker.postgresConfig, SomeEvent.empty, throwableToString).accepted

  override def generateOffset: Offset = Sequence(Random.nextLong())
}
