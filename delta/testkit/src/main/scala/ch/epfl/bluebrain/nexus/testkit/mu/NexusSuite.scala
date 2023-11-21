package ch.epfl.bluebrain.nexus.testkit.mu

import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, Generators}
import munit.FunSuite

class NexusSuite
    extends FunSuite
    with CollectionAssertions
    with EitherAssertions
    with Generators
    with CirceLiteral
    with EitherValues {

  implicit protected val classLoader: ClassLoader = getClass.getClassLoader

}
