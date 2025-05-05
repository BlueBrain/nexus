package ch.epfl.bluebrain.nexus.delta.kernel

import munit.FunSuite

class SecretSuite extends FunSuite {

  test("Secret should not expose its value when calling toString") {
    assertEquals(Secret("value").toString, "SECRET")
  }

}
