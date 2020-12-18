package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ElasticSearchViewsSpec
    extends AbstractDbSpec
    with AnyWordSpecLike
    with ElasticSearchViewBehaviours
    with ElasticSearchViewSTMBehaviours
    with ElasticSearchViewDecodingBehaviours
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestHelpers
