package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{AkkaPersistenceCassandraSpec, CassandraProjectionSpec}
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class CassandraSpecs
    extends Suites(new CassandraDatabaseDefinitionSpec, new CassandraProjectionSpec)
    with AkkaPersistenceCassandraSpec
    with DockerTestKit {}
