package ch.epfl.bluebrain.nexus.testkit.scalatest.bio

import ch.epfl.bluebrain.nexus.testkit.bio.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues

trait BioSpec extends BaseSpec with BIOValues with CatsIOValues with IOFixedClock
