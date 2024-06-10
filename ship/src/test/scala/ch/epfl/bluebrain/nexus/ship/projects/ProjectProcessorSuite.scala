package ch.epfl.bluebrain.nexus.ship.projects

import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, PrefixIri}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.IriPatcher
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class ProjectProcessorSuite extends NexusSuite {

  private val originalProject = ProjectRef.unsafe("org", "proj")
  private val targetProject   = ProjectRef.unsafe("target-org", "target-proj")

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https://openbrainplatform.com/"
  private val projectMapping = Map(originalProject -> targetProject)
  private val iriPatcher     = IriPatcher(originalPrefix, targetPrefix, projectMapping)

  test("Patch the project fields") {
    val description = Some("Project description")
    val base        = PrefixIri.unsafe(iri"https://bbp.epfl.ch/base")
    val vocab       = PrefixIri.unsafe(iri"https://bbp.epfl.ch/org/proj/vocab")

    val apiMappings = ApiMappings(
      "schema"     -> iri"https://bbp.epfl.ch/schema",
      "datashapes" -> iri"https://neuroshapes.org/dash/"
    )

    val obtained = ProjectProcessor.patchFields(iriPatcher)(description, base, vocab, apiMappings, enforceSchema = true)

    assertEquals(obtained.description, description)
    assertEquals(
      obtained.apiMappings,
      ApiMappings(
        "schema"     -> iri"https://openbrainplatform.com/schema",
        "datashapes" -> iri"https://neuroshapes.org/dash/"
      )
    )

    assertEquals(
      obtained.base,
      Some(PrefixIri.unsafe(iri"https://openbrainplatform.com/base"))
    )

    assertEquals(
      obtained.vocab,
      Some(PrefixIri.unsafe(iri"https://openbrainplatform.com/target-org/target-proj/vocab"))
    )
  }

}
