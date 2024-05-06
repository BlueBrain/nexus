package ch.epfl.bluebrain.nexus.ship.resources

import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class SourcePatcherSuite extends NexusSuite {

  test("Removing empty ids does not modify the source when the @id is non empty") {
    val source = json"""{ "@id": "bob-id", "name": "Bob" }"""
    assertEquals(SourcePatcher.removeEmptyIds(source), source)
  }

  test("Removing empty ids does not modify the source when there is no @id") {
    val source = json"""{ "name": "Bob" }"""
    assertEquals(SourcePatcher.removeEmptyIds(source), source)
  }

  test("Removing empty ids removes the @id field when it is empty") {
    val source = json"""{ "@id": "", "name": "Bob" }"""
    val expected = json"""{ "name": "Bob" }"""
    assertEquals(SourcePatcher.removeEmptyIds(source), expected)
  }

}
