package ch.epfl.bluebrain.nexus.ship.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{IdentityResolution, Priority}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class ResolverProcessorSuite extends NexusSuite {

  private val originalProject = ProjectRef.unsafe("bbp", "sscx")
  private val targetProject   = ProjectRef.unsafe("obp", "somato")

  private val projectMapper = ProjectMapper(Map(originalProject -> targetProject))

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https:/openbrainplatform.com/"

  private val iriPatcher = IriPatcher(originalPrefix, targetPrefix, Map(originalProject -> targetProject))

  private val priority = Priority.unsafe(42)

  test("Patching does not affect in project resolvers") {
    val original = InProjectValue(priority)

    val obtained = ResolverProcessor.patchValue(original, projectMapper, iriPatcher)
    assertEquals(obtained, original)
  }

  test("Patching a cross project resolver") {
    val unpatchedProject = ProjectRef.unsafe("neurosciencegraph", "datamodels")
    val originalProjects = NonEmptyList.of(unpatchedProject, originalProject)
    val originalType     = originalPrefix / originalProject.organization.value / originalProject.project.value / "Type"
    val original         = CrossProjectValue(
      Some("My resolver"),
      Some("My description"),
      priority,
      Set(nxv + "Schema", originalType),
      originalProjects,
      IdentityResolution.UseCurrentCaller
    )

    val expected = original.copy(
      projects = NonEmptyList.of(unpatchedProject, targetProject),
      resourceTypes =
        Set(nxv + "Schema", targetPrefix / targetProject.organization.value / targetProject.project.value / "Type")
    )
    val obtained = ResolverProcessor.patchValue(original, projectMapper, iriPatcher)
    assertEquals(obtained, expected)
  }

}
