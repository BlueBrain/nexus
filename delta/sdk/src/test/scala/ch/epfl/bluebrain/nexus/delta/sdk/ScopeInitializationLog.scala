package ch.epfl.bluebrain.nexus.delta.sdk
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IORef

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
  ): IO[Unit] =
    createdOrgs.update(_ + organization.label)

  override def onProjectCreation(
      project: Project,
      subject: Identity.Subject
  ): IO[Unit] =
    createdProjects.update(_ + project.ref)
}

object ScopeInitializationLog {

  def apply(): IO[ScopeInitializationLog] =
    for {
      createdOrgs     <- IORef.of(Set.empty[Label])
      createdProjects <- IORef.of(Set.empty[ProjectRef])
    } yield new ScopeInitializationLog(createdOrgs, createdProjects)

}
