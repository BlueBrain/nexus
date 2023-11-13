package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

trait CatsEffectSpec extends BaseSpec with CatsRunContext with CatsIOValues with FixedClock
