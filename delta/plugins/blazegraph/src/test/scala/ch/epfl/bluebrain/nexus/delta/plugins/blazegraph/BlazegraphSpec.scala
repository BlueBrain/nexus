package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClientSpec
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingSpec
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class BlazegraphSpec
    extends Suites(new BlazegraphIndexingSpec, new BlazegraphClientSpec, new BlazegraphViewsQuerySpec)
    with BlazegraphDocker
    with DockerTestKit
