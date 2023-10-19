package ch.epfl.bluebrain.nexus.testkit.scalatest

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

abstract class DeltaSpec extends AnyWordSpecLike with Matchers with EitherValuable with OptionValues with Inspectors
