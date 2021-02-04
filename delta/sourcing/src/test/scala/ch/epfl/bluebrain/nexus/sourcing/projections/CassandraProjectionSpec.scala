package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.sourcing.config.CassandraConfig
import com.datastax.oss.driver.api.core.uuid.Uuids

class CassandraProjectionSpec extends AkkaPersistenceCassandraSpec with ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  private val cassandraConfig = CassandraConfig(
    Set(s"${cassandraHostConfig.host}:${cassandraHostConfig.port}"),
    "delta",
    "delta_snapshot",
    "cassandra",
    Secret("cassandra"),
    keyspaceAutocreate = true,
    tablesAutocreate = true
  )

  override lazy val projections: Projection[SomeEvent] =
    Projection.cassandra(cassandraConfig, SomeEvent.empty, throwableToString).runSyncUnsafe()

  override def generateOffset: Offset = TimeBasedUUID(Uuids.timeBased())
}
