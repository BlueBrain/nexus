package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class IriPatcherSuite extends NexusSuite {

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https:/openbrainplatform.com/"

  private val originalProject = ProjectRef.unsafe("org", "proj")
  private val targetProject   = ProjectRef.unsafe("target-org", "target-proj")

  private val iriPatcher = IriPatcher(originalPrefix, targetPrefix, Map(originalProject -> targetProject))

  test("Keep the original if it starts by another prefix") {
    val original = iri"https://www.epfl.ch/something"
    assertEquals(iriPatcher(original), original)
  }

  test("Replace by the target prefix if the original prefix matches") {
    val original = iri"https://bbp.epfl.ch/something"
    val expected = iri"https:/openbrainplatform.com/something"
    assertEquals(iriPatcher(original), expected)
  }

  test("Replace the target prefix and the project reference if the original prefix matches") {
    val original = iri"https://bbp.epfl.ch/data/org/proj/id"
    val expected = iri"https:/openbrainplatform.com/data/target-org/target-proj/id"
    assertEquals(iriPatcher(original), expected)
  }

}
