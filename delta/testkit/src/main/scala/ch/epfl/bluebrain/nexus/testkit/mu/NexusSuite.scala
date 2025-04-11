package ch.epfl.bluebrain.nexus.testkit.mu

import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectEventually, CatsIOValues, MoreCatsEffectAssertions}
import ch.epfl.bluebrain.nexus.testkit.scalatest.{ClasspathResources, MUnitExtractValue}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, Generators}
import munit.CatsEffectSuite

abstract class NexusSuite
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
    with StreamAssertions
    with CatsEffectEventually
    with FixedClock
