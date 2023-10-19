package ch.epfl.bluebrain.nexus.testkit.ce

import ch.epfl.bluebrain.nexus.testkit.DeltaSpec

abstract class CatsSpec extends DeltaSpec with CatsRunContext with CatsEffectScalaTestAssertions
