package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import ch.epfl.bluebrain.nexus.testkit.ce.{CatsRunContext, IOFixedClock}
import ch.epfl.bluebrain.nexus.testkit.scalatest.DeltaSpec

abstract class CatsSpec extends DeltaSpec with CatsRunContext with CatsEffectScalaTestAssertions with IOFixedClock
