package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.projections.CassandraProjectionSpec
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import org.scalatest.{Suite, Suites}

class CassandraSpecs extends Suites() with CassandraDocker {

  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new CassandraDatabaseDefinitionSpec(this),
    new CassandraProjectionSpec(this)
  )
}
