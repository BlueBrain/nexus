package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClientSpec
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingSpec
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsQuerySpec
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphDocker
import org.scalatest.{Suite, Suites}

class BlazegraphSpec extends Suites() with BlazegraphDocker {
  override val nestedSuites: IndexedSeq[Suite] =
    Vector(
      new BlazegraphIndexingSpec(this),
      new BlazegraphClientSpec(this),
      new BlazegraphViewsQuerySpec(this)
    )
}
