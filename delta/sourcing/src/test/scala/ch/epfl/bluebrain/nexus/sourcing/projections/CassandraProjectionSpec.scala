package ch.epfl.bluebrain.nexus.sourcing.projections

import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.Cassandra.CassandraConfig

class CassandraProjectionSpec extends AkkaPersistenceCassandraSpec with ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  private val cassandraConfig = CassandraConfig(
    "projections",
    keyspaceAutoCreate = true,
    tablesAutoCreate = true,
    "'SimpleStrategy','replication_factor': 1"
  )

  override val projections: Projection[SomeEvent] =
    Projection.cassandra[SomeEvent](cassandraConfig).runSyncUnsafe()

  override val schemaMigration: SchemaMigration =
    SchemaMigration.cassandra(cassandraConfig).runSyncUnsafe()
}
