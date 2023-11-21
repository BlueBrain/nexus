package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.{BaseSpec, ClasspathResources, ScalaTestExtractValue}

trait CatsEffectSpec
    extends BaseSpec
    with CatsIOValues
    with ClasspathResources
    with ScalaTestExtractValue
    with FixedClock
