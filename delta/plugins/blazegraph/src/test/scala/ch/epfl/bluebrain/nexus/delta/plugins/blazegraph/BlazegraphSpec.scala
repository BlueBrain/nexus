package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClientSpec
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class BlazegraphSpec
    extends Suites(new BlazegraphIndexingSpec, new BlazegraphClientSpec)
    with BlazegraphDocker
    with DockerTestKit
