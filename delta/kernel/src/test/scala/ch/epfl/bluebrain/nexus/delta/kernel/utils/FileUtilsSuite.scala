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

  test("Extract the filename without the extension when there is a single dot") {
    val obtained = FileUtils.filenameWithoutExtension("my-file.json")
    val expected = Some("my-file")
    assertEquals(obtained, expected)
  }

  test("Extract the filename without the extension when there are several dots") {
    val obtained = FileUtils.filenameWithoutExtension("my.dotted.file.json")
    val expected = Some("my.dotted.file")
    assertEquals(obtained, expected)
  }

  test("Extract no filename when there is nothing before the dot") {
    val obtained = FileUtils.filenameWithoutExtension(".json")
    val expected = None
    assertEquals(obtained, expected)
  }

}
