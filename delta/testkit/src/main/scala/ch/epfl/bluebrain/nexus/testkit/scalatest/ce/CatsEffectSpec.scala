package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import ch.epfl.bluebrain.nexus.testkit.bio.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

trait CatsEffectSpec extends BaseSpec with CatsRunContext with CatsIOValues with IOFixedClock
