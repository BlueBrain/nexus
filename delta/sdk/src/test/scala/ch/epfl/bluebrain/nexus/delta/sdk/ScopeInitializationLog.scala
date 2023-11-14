package ch.epfl.bluebrain.nexus.delta.sdk
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import cats.effect.Ref

/**
  * Simple implementation that records created orgs and projects
  */
final class ScopeInitializationLog private (
    val createdOrgs: Ref[IO, Set[Label]],
    val createdProjects: Ref[IO, Set[ProjectRef]]
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
      createdOrgs     <- Ref.of[IO, Set[Label]](Set.empty)
      createdProjects <- Ref.of[IO, Set[ProjectRef]](Set.empty)
    } yield new ScopeInitializationLog(createdOrgs, createdProjects)

}
