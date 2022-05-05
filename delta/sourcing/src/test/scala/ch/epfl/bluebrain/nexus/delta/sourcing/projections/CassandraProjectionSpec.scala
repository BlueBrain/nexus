package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.actor.typed.ActorSystem
import akka.persistence.query.{Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import com.datastax.oss.driver.api.core.uuid.Uuids
import org.scalatest.DoNotDiscover

@DoNotDiscover
class CassandraProjectionSpec(docker: CassandraDocker) extends ProjectionSpec {

  implicit lazy val actorSystem: ActorSystem[Nothing] = AkkaPersistenceCassandraSpec.actorSystem(docker)

  override lazy val projections: Projection[SomeEvent] =
    Projection
      .cassandra(AkkaPersistenceCassandraSpec.cassandraConfig(docker), SomeEvent.empty, throwableToString)
      .accepted

  override def generateOffset: Offset = TimeBasedUUID(Uuids.timeBased())
}
