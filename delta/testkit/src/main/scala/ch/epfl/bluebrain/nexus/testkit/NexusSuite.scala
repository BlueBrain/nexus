package ch.epfl.bluebrain.nexus.testkit

import munit.FunSuite

class NexusSuite extends FunSuite {

  implicit protected val classLoader: ClassLoader = getClass.getClassLoader

  protected def group(name: String)(thunk: => Unit): Unit = {
    val countBefore     = munitTestsBuffer.size
    val _               = thunk
    val countAfter      = munitTestsBuffer.size
    val countRegistered = countAfter - countBefore
    val registered      = munitTestsBuffer.toList.drop(countBefore)
    (0 until countRegistered).foreach(_ => munitTestsBuffer.remove(countBefore))
    registered.foreach(t => munitTestsBuffer += t.withName(s"$name - ${t.name}"))
  }

}
