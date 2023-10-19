package ch.epfl.bluebrain.nexus.testkit.scalatest

import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

abstract class BaseSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with OptionValues
    with Inspectors
    with TestHelpers
    with TestMatchers
