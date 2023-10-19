package ch.epfl.bluebrain.nexus.testkit

import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

abstract class DeltaSpec extends AnyWordSpecLike with Matchers with EitherValuable with OptionValues with Inspectors
