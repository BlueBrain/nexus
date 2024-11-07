package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.{noopStore, ScopeInitErrorRow}
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class ProjectHealerSuite extends NexusSuite {

  private def scopeInitializer(projectInitializationWasExecuted: Ref[IO, Boolean]) = new ScopeInitializer {
    override def initializeOrganization(organizationResource: OrganizationResource)(implicit
        caller: Identity.Subject
    ): IO[Unit]                                                                                      = IO.unit
    override def initializeProject(project: ProjectRef)(implicit caller: Identity.Subject): IO[Unit] =
      projectInitializationWasExecuted.set(true)
  }

  private def failingScopeInitializer = new ScopeInitializer {
    override def initializeOrganization(organizationResource: OrganizationResource)(implicit
        caller: Identity.Subject
    ): IO[Unit]                                                                                      =
      IO.raiseError(ScopeInitializationFailed("failed during org creation"))
    override def initializeProject(project: ProjectRef)(implicit caller: Identity.Subject): IO[Unit] =
      IO.raiseError(ScopeInitializationFailed("failed during project creation"))
  }

  private def errorStore(deletionWasExecuted: Ref[IO, Boolean]): ScopeInitializationErrorStore =
    new ScopeInitializationErrorStore {
      override def save(entityType: EntityType, project: ProjectRef, e: ScopeInitializationFailed): IO[Unit] = IO.unit
      override def fetch: IO[List[ScopeInitErrorRow]]                                                        = IO.pure(List.empty)
      override def delete(project: ProjectRef): IO[Unit]                                                     = deletionWasExecuted.set(true)
    }

  private val project = ProjectRef(Label.unsafe(genString()), Label.unsafe(genString()))

  private val saRealm: Label     = Label.unsafe("service-accounts")
  private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))

  test("The project healer calls the ScopeInitializer's initialize project method") {
    val projectInitializationWasExecuted = Ref.unsafe[IO, Boolean](false)
    val projectHealer                    = ProjectHealer(noopStore, scopeInitializer(projectInitializationWasExecuted), sa)

    projectHealer.heal(project) >> projectInitializationWasExecuted.get.assertEquals(true)
  }

  test("The project healer should delete the errors after a successful project initialization") {
    val deletionWasExecuted = Ref.unsafe[IO, Boolean](false)
    val projectHealer       =
      ProjectHealer(errorStore(deletionWasExecuted), ScopeInitializer.noop, sa)

    projectHealer.heal(project) >> deletionWasExecuted.get.assertEquals(true)
  }

  test("If the project initialization fails again, the errors should not be deleted") {
    val deletionWasExecuted = Ref.unsafe[IO, Boolean](false)
    val projectHealer       =
      ProjectHealer(errorStore(deletionWasExecuted), failingScopeInitializer, sa)

    projectHealer.heal(project).intercept[ScopeInitializationFailed] >>
      deletionWasExecuted.get.assertEquals(false)
  }

}
