package ch.epfl.bluebrain.nexus.testkit

import munit.FunSuite

class NexusSuite extends FunSuite {

  implicit protected val classLoader: ClassLoader = getClass.getClassLoader

}
