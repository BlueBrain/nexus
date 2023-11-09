package ch.epfl.bluebrain.nexus.testkit.scalatest.bio

import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

trait BioSpec extends BaseSpec with BIOValues with FixedClock
