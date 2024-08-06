package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.Path

class DirectoryReaderSuite extends NexusSuite {

  test("Filter out non json files") {
    val input = List(Path("import/0000.txt"))
    assertEquals(DirectoryReader(input, Offset.start), List.empty)
  }

  test("Filter out files with non-offset names") {
    val input = List(Path("import/f4il.json"))
    assertEquals(DirectoryReader(input, Offset.start), List.empty)
  }

  test("Sort input files") {
    val input    = List(
      Path("import/004.json"),
      Path("import/001.json"),
      Path("import/002.json")
    )
    val expected = List(
      Path("import/001.json"),
      Path("import/002.json"),
      Path("import/004.json")
    )
    assertEquals(DirectoryReader(input, Offset.start), expected)
  }

  test("Skip files with offset 21") {
    val input    = List(
      Path("import/0030.json"),
      Path("import/0020.json"),
      Path("import/0010.json"),
      Path("import/0040.json")
    )
    val expected = List(
      Path("import/0020.json"),
      Path("import/0030.json"),
      Path("import/0040.json")
    )
    assertEquals(DirectoryReader(input, Offset.at(21L)), expected)
  }

  test("Skip files with offset 31") {
    val input    = List(
      Path("import/0030.json"),
      Path("import/0020.json"),
      Path("import/0010.json"),
      Path("import/0040.json")
    )
    val expected = List(
      Path("import/0030.json"),
      Path("import/0040.json")
    )
    assertEquals(DirectoryReader(input, Offset.at(31L)), expected)
  }

  test("Skip files with offset 41") {
    val input    = List(
      Path("import/0030.json"),
      Path("import/0020.json"),
      Path("import/0010.json"),
      Path("import/0040.json")
    )
    val expected = List(
      Path("import/0040.json")
    )
    assertEquals(DirectoryReader(input, Offset.at(41L)), expected)
  }

}
