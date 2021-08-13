package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.actor.typed.ActorSystem
import akka.persistence.query.{Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.AkkaPersistenceCassandraSpec.cassandraConfig
import com.datastax.oss.driver.api.core.uuid.Uuids
import org.scalatest.DoNotDiscover

@DoNotDiscover
class CassandraProjectionSpec extends ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global
  implicit val actorSystem: ActorSystem[Nothing] = AkkaPersistenceCassandraSpec.actorSystem

  override lazy val projections: Projection[SomeEvent] =
    Projection.cassandra(cassandraConfig, SomeEvent.empty, throwableToString).runSyncUnsafe()

  override def generateOffset: Offset = TimeBasedUUID(Uuids.timeBased())
}
