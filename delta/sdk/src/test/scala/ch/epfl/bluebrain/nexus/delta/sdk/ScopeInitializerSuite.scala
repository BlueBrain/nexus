package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.ScopeInitErrorRow
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class ScopeInitializerSuite extends NexusSuite {

  implicit private val subject: Subject = User("myuser", Label.unsafe("myrealm"))

  private val fail = new ScopeInitialization {
    override def onOrganizationCreation(organization: Organization, subject: Subject): IO[Unit] =
      IO.raiseError(ScopeInitializationFailed("failed during org creation"))
    override def onProjectCreation(project: ProjectRef, subject: Subject): IO[Unit]             =
      IO.raiseError(ScopeInitializationFailed("failed during project creation"))
    override def entityType: EntityType                                                         = EntityType("fail")
  }

  private val projectSignal = "project step executed"
  private val orgSignal     = "org step executed"

  private def success(ref: Ref[IO, String]) = new ScopeInitialization {
    override def onOrganizationCreation(organization: Organization, subject: Subject): IO[Unit] =
      ref.set(orgSignal)

    override def onProjectCreation(project: ProjectRef, subject: Subject): IO[Unit] =
      ref.set(projectSignal)

    override def entityType: EntityType = EntityType("success")
  }

  private val failingScopeInitializer = ScopeInitializer.withoutErrorStore(Set(fail))

  private val org: OrganizationResource =
    OrganizationGen.resourceFor(OrganizationGen.organization("myorg"), 1, subject)
  private val project: ProjectResource  =
    ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject"), 1, subject)

  private def simpleErrorStore(errors: Ref[IO, List[ScopeInitErrorRow]]) = new ScopeInitializationErrorStore {
    override def save(entityType: EntityType, project: ProjectRef, e: ScopeInitializationFailed): IO[Unit] =
      errors.update(
        _ :+ ScopeInitErrorRow(0, entityType, project.organization, project.project, e.getMessage, Instant.EPOCH)
      )

    override def fetch: IO[List[ScopeInitErrorRow]] =
      errors.get
  }

  val projectRef = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproject"))

  test("A ScopeInitializer should succeed if there are no init steps") {
    ScopeInitializer.noop.initializeOrganization(org) >>
      ScopeInitializer.noop.initializeProject(projectRef)
  }

  test("A ScopeInitializer should fail if there is a failing org init step") {
    failingScopeInitializer
      .initializeOrganization(org)
      .intercept[OrganizationInitializationFailed]
  }

  test("A ScopeInitializer should fail if there is a failing project init step") {
    failingScopeInitializer
      .initializeProject(projectRef)
      .intercept[ProjectInitializationFailed]
  }

  test("The ScopeInitializer should execute the provided init step upon org creation") {
    val wasExecuted      = Ref.unsafe[IO, String]("")
    val scopeInitializer = ScopeInitializer.withoutErrorStore(Set(success(wasExecuted)))

    scopeInitializer.initializeOrganization(org) >>
      assertIO(wasExecuted.get, orgSignal)
  }

  test("The ScopeInitializer should execute the provided init steps") {
    val wasExecuted      = Ref.unsafe[IO, String]("")
    val scopeInitializer = ScopeInitializer.withoutErrorStore(Set(success(wasExecuted)))

    scopeInitializer.initializeProject(projectRef) >>
      assertIO(wasExecuted.get, projectSignal)
  }

  test("A failing step should not prevent a successful one to run on org creation") {
    val wasExecuted      = Ref.unsafe[IO, String]("")
    val scopeInitializer = ScopeInitializer.withoutErrorStore(Set(success(wasExecuted), fail))

    scopeInitializer.initializeOrganization(org).intercept[OrganizationInitializationFailed] >>
      assertIO(wasExecuted.get, orgSignal)
  }

  test("A failing step should not prevent a successful one to run on project creation") {
    val wasExecuted      = Ref.unsafe[IO, String]("")
    val scopeInitializer = ScopeInitializer.withoutErrorStore(Set(success(wasExecuted), fail))

    scopeInitializer.initializeProject(projectRef).intercept[ProjectInitializationFailed] >>
      assertIO(wasExecuted.get, projectSignal)
  }

  test("Save an error upon project failure") {
    val errors           = Ref.unsafe[IO, List[ScopeInitErrorRow]](List.empty)
    val scopeInitializer = ScopeInitializer(Set(fail), simpleErrorStore(errors))
    // format: off
    val expectedErrorRow = List(ScopeInitErrorRow(0, EntityType("fail"), org.value.label, project.value.ref.project, "failed during project creation", Instant.EPOCH))
    // format: on

    scopeInitializer.initializeProject(projectRef).intercept[ProjectInitializationFailed] >>
      assertIO(errors.get, expectedErrorRow)
  }

}
