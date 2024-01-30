package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectIsMarkedForDeletion, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.Quota
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.model.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class FetchContextSuite extends NexusSuite {

  implicit private val subject: Subject = Identity.Anonymous

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
    case `activeProject`     => IO.pure(ProjectGen.resourceFor(activeProjectValue))
    case `deletedProject`    =>
      IO.pure(
        ProjectGen.resourceFor(
          ProjectGen.project(deletedProject.organization.value, deletedProject.project.value),
          markedForDeletion = true
        )
      )
    case `deprecatedProject` => IO.pure(ProjectGen.resourceFor(deprecatedProjectValue, deprecated = true))
    case _                   => IO.raiseError(ProjectNotFound(ref))
  }

  private def quotas(resources: Boolean, events: Boolean) = new Quotas {
    override def fetch(ref: ProjectRef): IO[Quota] = IO.pure(Quota(Some(0), Some(0)))

    override def reachedForResources(ref: ProjectRef, subject: Subject): IO[Unit] =
      IO.raiseWhen(resources)(QuotaResourcesReached(ref, 0))

    override def reachedForEvents(ref: ProjectRef, subject: Subject): IO[Unit] =
      IO.raiseWhen(events)(QuotaEventsReached(ref, 0))
  }

  private def fetchContext(quotasResources: Boolean, quotasEvents: Boolean) = FetchContext(
    fetchActiveOrganization,
    ApiMappings.empty,
    fetchProject,
    quotas(quotasResources, quotasEvents)
  )

  test("Successfully get a context for an active project on read") {
    fetchContext(quotasResources = true, quotasEvents = true)
      .onRead(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Successfully get a context for a deprecated project on read") {
    fetchContext(quotasResources = true, quotasEvents = true)
      .onRead(deprecatedProject)
      .assertEquals(deprecatedProjectValue.context)
  }

  test("Fail getting a context for a project marked as deleted on read") {
    fetchContext(quotasResources = true, quotasEvents = true)
      .onRead(deletedProject)
      .interceptEquals(ProjectIsMarkedForDeletion(deletedProject))
  }

  test("Successfully get a context for an active project on create if quota is not reached") {
    fetchContext(quotasResources = false, quotasEvents = false)
      .onRead(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Fail getting a context for an active project on create if quota for resources is not reached") {
    fetchContext(quotasResources = true, quotasEvents = false)
      .onCreate(activeProject)
      .interceptEquals(QuotaResourcesReached(activeProject, 0))
  }

  test("Fail getting a context for a deprecated project on create") {
    fetchContext(quotasResources = false, quotasEvents = false)
      .onCreate(deprecatedProject)
      .interceptEquals(ProjectIsDeprecated(deprecatedProject))
  }

  test("Fail getting a context for a project marked as deleted on create") {
    fetchContext(quotasResources = false, quotasEvents = false)
      .onCreate(deletedProject)
      .interceptEquals(ProjectIsMarkedForDeletion(deletedProject))
  }

  test("Successfully get a context for an active project on modify if quotas are not reached") {
    fetchContext(quotasResources = false, quotasEvents = false)
      .onModify(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Successfully get a context for an active project on modify if only resource quota is reached") {
    fetchContext(quotasResources = true, quotasEvents = false)
      .onModify(activeProject)
      .assertEquals(activeProjectValue.context)
  }

  test("Fail getting a context for an active project on create if event quotas is reached") {
    fetchContext(quotasResources = false, quotasEvents = true)
      .onModify(activeProject)
      .interceptEquals(QuotaEventsReached(activeProject, 0))
  }

  test("Fail getting a context for a deprecated project on modify") {
    fetchContext(quotasResources = false, quotasEvents = false)
      .onModify(deprecatedProject)
      .interceptEquals(ProjectIsDeprecated(deprecatedProject))
  }

  test("Fail getting a context for a project marked as deleted on modify") {
    fetchContext(quotasResources = false, quotasEvents = false)
      .onModify(deletedProject)
      .interceptEquals(ProjectIsMarkedForDeletion(deletedProject))
  }

}
