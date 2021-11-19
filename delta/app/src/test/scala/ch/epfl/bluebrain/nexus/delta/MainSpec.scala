package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithTimeouts
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class MainSpec
    extends Suites(new PostgresMainSpec, new CassandraMainSpec)
    with ElasticSearchDocker
    with DockerTestKit
    with DockerKitWithTimeouts
