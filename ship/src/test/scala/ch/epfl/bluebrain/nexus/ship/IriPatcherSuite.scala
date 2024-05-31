package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class IriPatcherSuite extends NexusSuite {

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https:/openbrainplatform.com/"

  private val iriPatcher = IriPatcher(originalPrefix, targetPrefix)

  test("Keep the original if it starts by another prefix") {
    val original = iri"https://www.epfl.ch/something"
    assertEquals(iriPatcher(original), original)
  }

  test("Replace by the target prefix if the original prefix matches") {
    val original = iri"https://bbp.epfl.ch/something"
    val expected = iri"https:/openbrainplatform.com/something"
    assertEquals(iriPatcher(original), expected)
  }

}
