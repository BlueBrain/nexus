package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.kernel.Resource
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

object EventMetricsIndex {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader()

  trait Fixture { self: CatsEffectSuite =>
    val metricsIndex: IOFixture[MetricsIndexDef] =
      ResourceSuiteLocalFixture("metrics-index", Resource.eval(MetricsIndexDef("test", loader)))
  }

}
