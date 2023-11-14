package ch.epfl.bluebrain.nexus.testkit.scalatest

import ch.epfl.bluebrain.nexus.testkit.{CirceEq, CirceLiteral, Generators}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

trait BaseSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with OptionValues
    with Inspectors
    with TestMatchers
    with Generators
    with CirceLiteral
    with CirceEq
