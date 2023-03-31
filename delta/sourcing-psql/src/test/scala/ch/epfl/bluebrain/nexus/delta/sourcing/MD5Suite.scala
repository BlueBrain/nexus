package ch.epfl.bluebrain.nexus.delta.sourcing

import munit.FunSuite

class MD5Suite extends FunSuite {

  test("a string should have the correct MD5 hash") {
    val input = "bbp/atlas"
    val hash = MD5.hash(input)
    val expectedHash = "5741353a5fa12bd21cc6c19ecc97b256"
    assertEquals(hash, expectedHash)
  }

}
