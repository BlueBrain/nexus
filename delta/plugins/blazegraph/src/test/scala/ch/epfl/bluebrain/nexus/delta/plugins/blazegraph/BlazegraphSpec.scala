package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClientSpec
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinatorSpec
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsQuerySpec
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class BlazegraphSpec
    extends Suites(new BlazegraphIndexingCoordinatorSpec, new BlazegraphClientSpec, new BlazegraphViewsQuerySpec)
    with BlazegraphDocker
    with DockerTestKit
