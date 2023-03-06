package ch.epfl.bluebrain.nexus.delta.sdk
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IORef
import monix.bio.{IO, UIO}

/**
  * Simple implementation that records created orgs and projects
  */
final class ScopeInitializationLog private (
    val createdOrgs: IORef[Set[Label]],
    val createdProjects: IORef[Set[ProjectRef]]
) extends ScopeInitialization {

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ServiceError.ScopeInitializationFailed, Unit] =
    createdOrgs.update(_ + organization.label)

  override def onProjectCreation(
      project: Project,
      subject: Identity.Subject
  ): IO[ServiceError.ScopeInitializationFailed, Unit] =
    createdProjects.update(_ + project.ref)
}

object ScopeInitializationLog {

  def apply(): UIO[ScopeInitializationLog] =
    for {
      createdOrgs     <- IORef.of(Set.empty[Label])
      createdProjects <- IORef.of(Set.empty[ProjectRef])
    } yield new ScopeInitializationLog(createdOrgs, createdProjects)

}
