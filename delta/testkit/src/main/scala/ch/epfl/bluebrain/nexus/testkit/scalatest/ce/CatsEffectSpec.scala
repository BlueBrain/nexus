package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import ch.epfl.bluebrain.nexus.testkit.ce.{CatsRunContext, IOFixedClock}
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

abstract class CatsEffectSpec extends BaseSpec with CatsRunContext with CatsIOValues with IOFixedClock
