package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectIsMarkedForDeletion}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class FetchContextSuite extends NexusSuite {

  private val activeOrg     = Label.unsafe("org")
  private val deprecatedOrg = Label.unsafe("deprecated")

  private def fetchActiveOrganization(label: Label): IO[Unit] = label match {
    case `activeOrg`     => IO.unit
    case `deprecatedOrg` => IO.raiseError(OrganizationIsDeprecated(deprecatedOrg))
    case _               => IO.raiseError(OrganizationNotFound(label))
  }

  private val activeProject     = ProjectRef.unsafe("org", "proj")
  private val deletedProject    = ProjectRef.unsafe("org", "deleted")
  private val deprecatedProject = ProjectRef.unsafe("org", "deprecated")

  private val activeProjectValue     = ProjectGen.project(activeProject.organization.value, activeProject.project.value)
  private val deprecatedProjectValue =
    ProjectGen.project(deprecatedProject.organization.value, deprecatedProject.project.value)

  private def fetchProject(ref: ProjectRef) = ref match {
    case `activeProject`     => IO.some(ProjectGen.resourceFor(activeProjectValue))
    case `deletedProject`    =>
      IO.some(
        ProjectGen.resourceFor(
          ProjectGen.project(deletedProject.organization.value, deletedProject.project.value),
          markedForDeletion = true
        )
      )
    case `deprecatedProject` => IO.some(ProjectGen.resourceFor(deprecatedProjectValue, deprecated = true))
    case _                   => IO.none
  }

  private def fetchContext = FetchContext(
    fetchActiveOrganization,
    ApiMappings.empty,
    (project: ProjectRef, _: Boolean) => fetchProject(project)
  )

  test("Successfully get a context for an active project on read") {
    fetchContext
      .onRead(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Successfully get a context for a deprecated project on read") {
    fetchContext
      .onRead(deprecatedProject)
      .assertEquals(deprecatedProjectValue.context)
  }

  test("Fail getting a context for a project marked as deleted on read") {
    fetchContext
      .onRead(deletedProject)
      .interceptEquals(ProjectIsMarkedForDeletion(deletedProject))
  }

  test("Successfully get a context for an active project on create") {
    fetchContext
      .onRead(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Fail getting a context for a deprecated project on create") {
    fetchContext
      .onCreate(deprecatedProject)
      .interceptEquals(ProjectIsDeprecated(deprecatedProject))
  }

  test("Fail getting a context for a project marked as deleted on create") {
    fetchContext
      .onCreate(deletedProject)
      .interceptEquals(ProjectIsMarkedForDeletion(deletedProject))
  }

  test("Successfully get a context for an active project on modify") {
    fetchContext
      .onModify(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Fail getting a context for a deprecated project on modify") {
    fetchContext
      .onModify(deprecatedProject)
      .interceptEquals(ProjectIsDeprecated(deprecatedProject))
  }

  test("Fail getting a context for a project marked as deleted on modify") {
    fetchContext
      .onModify(deletedProject)
      .interceptEquals(ProjectIsMarkedForDeletion(deletedProject))
  }

}
