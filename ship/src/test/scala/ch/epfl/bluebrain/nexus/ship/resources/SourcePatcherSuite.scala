package ch.epfl.bluebrain.nexus.ship.resources

import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.ship.IriPatcher
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
    val source   = json"""{ "@id": "", "name": "Bob" }"""
    val expected = json"""{ "name": "Bob" }"""
    assertEquals(SourcePatcher.removeEmptyIds(source), expected)
  }

  test("Patch iris in original payload") {
    val originalPrefix = iri"https://bbp.epfl.ch/"
    val targetPrefix   = iri"https://openbrainplatform.com/"
    val iriPatcher     = IriPatcher(originalPrefix, targetPrefix)
    val template       = "payload/sample-neuromorphology-entity.json"
    for {
      originalPayload <- loader.jsonContentOf(template, "prefix" -> originalPrefix)
      expectedPatched <- loader.jsonContentOf(template, "prefix" -> targetPrefix)
      result           = SourcePatcher.patchIris(originalPayload, iriPatcher)
    } yield {
      assertEquals(result, expectedPatched)
    }
  }

}
