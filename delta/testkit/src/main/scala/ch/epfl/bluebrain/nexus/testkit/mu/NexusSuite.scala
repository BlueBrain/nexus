package ch.epfl.bluebrain.nexus.testkit.mu

import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectEventually, CatsIOValues, CatsStreamAssertions, MoreCatsEffectAssertions}
import ch.epfl.bluebrain.nexus.testkit.scalatest.{ClasspathResources, MUnitExtractValue}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, Generators}
import munit.CatsEffectSuite

class NexusSuite
    extends CatsEffectSuite
    with MoreCatsEffectAssertions
    with CollectionAssertions
    with EitherAssertions
    with Generators
    with CirceLiteral
    with EitherValues
    with MUnitExtractValue
    with ClasspathResources
    with CatsIOValues
    with CatsStreamAssertions
    with CatsEffectEventually
    with FixedClock
