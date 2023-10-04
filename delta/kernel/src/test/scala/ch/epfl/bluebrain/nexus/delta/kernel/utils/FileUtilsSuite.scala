package ch.epfl.bluebrain.nexus.delta.kernel.utils

import munit.FunSuite

class FileUtilsSuite extends FunSuite {

  test("Detect json extension") {
    val obtained = FileUtils.extension("my-file.json")
    val expected = Some("json")
    assertEquals(obtained, expected)
  }

  test("Detect zip extension") {
    val obtained = FileUtils.extension("my-file.json.zip")
    val expected = Some("zip")
    assertEquals(obtained, expected)
  }

  test("Detect no extension") {
    val obtained = FileUtils.extension("my-file")
    val expected = None
    assertEquals(obtained, expected)
  }

}
